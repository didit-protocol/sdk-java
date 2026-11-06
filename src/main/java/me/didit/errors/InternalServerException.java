package me.didit.errors;

import com.fasterxml.jackson.databind.JsonNode;
import me.didit.core.Headers;
import org.jspecify.annotations.Nullable;

/** The API failed (5xx). Idempotent requests were already retried. */
public class InternalServerException extends ApiStatusException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message a summary of the failure
   * @param statusCode the HTTP status, 500 or above
   * @param body the parsed JSON body, or {@code null}
   * @param headers the response headers
   */
  public InternalServerException(
      String message, int statusCode, @Nullable JsonNode body, Headers headers) {
    super(message, statusCode, body, headers);
  }
}
