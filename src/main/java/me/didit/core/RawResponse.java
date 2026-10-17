package me.didit.core;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * An HTTP response with its parsed body: status, headers and request id (DESIGN 7).
 *
 * @param <T> the parsed body type
 */
public final class RawResponse<T extends @Nullable Object> {
  private final int statusCode;
  private final Headers headers;
  private final T data;

  /**
   * Creates the response.
   *
   * @param statusCode the HTTP status
   * @param headers the response headers
   * @param data the parsed body
   */
  public RawResponse(int statusCode, Headers headers, T data) {
    this.statusCode = statusCode;
    this.headers = headers;
    this.data = data;
  }

  /**
   * The HTTP status.
   *
   * @return the status
   */
  public int statusCode() {
    return statusCode;
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
   * @return the request id, if present
   */
  public Optional<String> requestId() {
    return headers.requestId();
  }

  /**
   * The parsed body; {@code null} for operations without a response body.
   *
   * @return the body
   */
  public T data() {
    return data;
  }

  @Override
  public String toString() {
    return "RawResponse{status=" + statusCode + ", requestId=" + requestId().orElse("none") + "}";
  }
}
