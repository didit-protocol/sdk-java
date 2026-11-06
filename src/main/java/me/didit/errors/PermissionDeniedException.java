package me.didit.errors;

import com.fasterxml.jackson.databind.JsonNode;
import me.didit.core.Headers;
import org.jspecify.annotations.Nullable;

/** The credentials have no access to this resource or action (403). */
public class PermissionDeniedException extends ApiStatusException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message a summary of the failure
   * @param body the parsed JSON body, or {@code null}
   * @param headers the response headers
   */
  public PermissionDeniedException(String message, @Nullable JsonNode body, Headers headers) {
    super(message, 403, body, headers);
  }
}
