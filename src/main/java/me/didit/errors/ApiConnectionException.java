package me.didit.errors;

import org.jspecify.annotations.Nullable;

/** No HTTP response was received: DNS, TCP, TLS or I/O failure. */
public class ApiConnectionException extends DiditException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what failed
   * @param cause the underlying I/O error
   */
  public ApiConnectionException(String message, @Nullable Throwable cause) {
    super(message, cause);
  }
}
