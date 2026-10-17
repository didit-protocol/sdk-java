package me.didit.errors;

import org.jspecify.annotations.Nullable;

/** Base class of every exception thrown by the Didit SDK. */
public class DiditException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what went wrong; never contains the API key
   */
  public DiditException(String message) {
    super(message);
  }

  /**
   * Creates the exception.
   *
   * @param message what went wrong; never contains the API key
   * @param cause the underlying cause
   */
  public DiditException(String message, @Nullable Throwable cause) {
    super(message, cause);
  }
}
