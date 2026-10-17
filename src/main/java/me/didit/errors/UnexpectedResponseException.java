package me.didit.errors;

import org.jspecify.annotations.Nullable;

/**
 * The API answered something this SDK cannot use: a success body that is not JSON, a field with
 * an unexpected type, a required field that is missing, or a next page link to another origin or
 * back to a page already read.
 */
public class UnexpectedResponseException extends DiditException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what was unexpected
   */
  public UnexpectedResponseException(String message) {
    super(message);
  }

  /**
   * Creates the exception.
   *
   * @param message what was unexpected
   * @param cause the parsing error
   */
  public UnexpectedResponseException(String message, @Nullable Throwable cause) {
    super(message, cause);
  }
}
