package me.didit.errors;

import com.fasterxml.jackson.databind.JsonNode;
import me.didit.core.Headers;
import org.jspecify.annotations.Nullable;

/** The resource does not exist or is outside the credentials' scope (404). */
public class NotFoundException extends ApiStatusException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message a summary of the failure
   * @param body the parsed JSON body, or {@code null}
   * @param headers the response headers
   */
  public NotFoundException(String message, @Nullable JsonNode body, Headers headers) {
    super(message, 404, body, headers);
  }
}
