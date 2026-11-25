package me.didit.errors;

import com.fasterxml.jackson.databind.JsonNode;
import me.didit.core.Headers;
import org.jspecify.annotations.Nullable;

/** Too many requests (429). The SDK already retried; slow down. */
public class RateLimitException extends ApiStatusException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message a summary of the failure
   * @param body the parsed JSON body, or {@code null}
   * @param headers the response headers
   */
  public RateLimitException(String message, @Nullable JsonNode body, Headers headers) {
    super(message, 429, body, headers);
  }
}
