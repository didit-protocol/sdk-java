package me.didit.errors;

import org.jspecify.annotations.Nullable;

/** The request timed out before a complete response was received. */
public class ApiTimeoutException extends ApiConnectionException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what timed out
   * @param cause the underlying timeout, if any
   */
  public ApiTimeoutException(String message, @Nullable Throwable cause) {
    super(message, cause);
  }
}
