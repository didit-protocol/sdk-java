package me.didit.errors;

/**
 * The client or the call is misconfigured: a missing API key, organization id or application id, an
 * empty webhook secret or an invalid path parameter. Thrown before any request is sent.
 */
public class ConfigurationException extends DiditException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what is missing or invalid
   */
  public ConfigurationException(String message) {
    super(message);
  }
}
