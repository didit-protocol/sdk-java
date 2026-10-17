package me.didit.errors;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import me.didit.core.Headers;
import org.jspecify.annotations.Nullable;

/**
 * The API answered with a status outside 2xx. Subclasses name the common statuses; {@link
 * #create} picks the right one.
 */
public class ApiStatusException extends DiditException {
  private static final long serialVersionUID = 1L;

  private final int statusCode;
  private final transient @Nullable JsonNode body;
  private final transient Headers headers;
  private final @Nullable String requestId;

  /**
   * Creates the exception.
   *
   * @param message a summary of the failure
   * @param statusCode the HTTP status
   * @param body the parsed JSON body, or {@code null} when the body is not JSON
   * @param headers the response headers
   */
  public ApiStatusException(
      String message, int statusCode, @Nullable JsonNode body, Headers headers) {
    super(message);
    this.statusCode = statusCode;
    this.body = body == null ? null : body.deepCopy();
    this.headers = headers;
    this.requestId = headers.requestId().orElse(null);
  }

  /**
   * The HTTP status code.
   *
   * @return the status
   */
  public int statusCode() {
    return statusCode;
  }

  /**
   * The response body parsed as JSON, when it is JSON.
   *
   * @return a copy of the body
   */
  public Optional<JsonNode> body() {
    return body == null ? Optional.empty() : Optional.of(body.deepCopy());
  }

  /**
   * The response headers.
   *
   * @return the headers
   */
  public Headers headers() {
    return headers;
  }

  /**
   * The request id the API assigned, for support requests.
   *
   * @return the request id, if the response carried one
   */
  public Optional<String> requestId() {
    return Optional.ofNullable(requestId);
  }

  /**
   * Builds the exception that matches {@code statusCode}.
   *
   * @param statusCode the HTTP status
   * @param body the parsed JSON body, or {@code null}
   * @param headers the response headers
   * @param context {@code METHOD path} of the request, for the message
   * @return the exception to throw
   */
  public static ApiStatusException create(
      int statusCode, @Nullable JsonNode body, Headers headers, String context) {
    String message = message(statusCode, body, headers, context);
    switch (statusCode) {
      case 400:
        return new BadRequestException(message, body, headers);
      case 401:
        return new AuthenticationException(message, body, headers);
      case 403:
        return new PermissionDeniedException(message, body, headers);
      case 404:
        return new NotFoundException(message, body, headers);
      case 409:
        return new ConflictException(message, body, headers);
      case 422:
        return new UnprocessableEntityException(message, body, headers);
      case 429:
        return new RateLimitException(message, body, headers);
      default:
        if (statusCode >= 500) {
          return new InternalServerException(message, statusCode, body, headers);
        }
        return new ApiStatusException(message, statusCode, body, headers);
    }
  }

  private static String message(
      int statusCode, @Nullable JsonNode body, Headers headers, String context) {
    StringBuilder out = new StringBuilder();
    out.append(context).append(" failed with status ").append(statusCode);
    String detail = detail(body);
    if (!detail.isEmpty()) {
      out.append(": ").append(detail);
    }
    headers.requestId().ifPresent(id -> out.append(" (request id ").append(id).append(')'));
    return out.toString();
  }

  private static String detail(@Nullable JsonNode body) {
    if (body == null) {
      return "";
    }
    for (String key : new String[] {"detail", "error_description", "message", "error"}) {
      JsonNode value = body.get(key);
      if (value != null && value.isTextual() && !value.asText().isEmpty()) {
        return truncate(value.asText());
      }
    }
    String text = body.toString();
    return truncate(text);
  }

  private static String truncate(String text) {
    return text.length() > 300 ? text.substring(0, 300) + "..." : text;
  }
}
