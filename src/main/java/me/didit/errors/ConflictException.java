package me.didit.errors;

import com.fasterxml.jackson.databind.JsonNode;
import me.didit.core.Headers;
import org.jspecify.annotations.Nullable;

/** The request conflicts with the current state of the resource (409). */
public class ConflictException extends ApiStatusException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message a summary of the failure
   * @param body the parsed JSON body, or {@code null}
   * @param headers the response headers
   */
  public ConflictException(String message, @Nullable JsonNode body, Headers headers) {
    super(message, 409, body, headers);
  }
}
