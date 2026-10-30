package me.didit.errors;

import com.fasterxml.jackson.databind.JsonNode;
import me.didit.core.Headers;
import org.jspecify.annotations.Nullable;

/** The API key or token is missing, wrong, revoked or expired (401). */
public class AuthenticationException extends ApiStatusException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message a summary of the failure
   * @param body the parsed JSON body, or {@code null}
   * @param headers the response headers
   */
  public AuthenticationException(String message, @Nullable JsonNode body, Headers headers) {
    super(message, 401, body, headers);
  }
}
