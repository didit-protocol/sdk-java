package me.didit.errors;

/**
 * A webhook delivery failed verification (DESIGN 9.3). Answer it with 400 or 401 and do not act
 * on its content; {@link #reason()} says why.
 */
public class WebhookVerificationException extends DiditException {
  private static final long serialVersionUID = 1L;

  /** Why a delivery was rejected. */
  public enum Reason {
    /** No signature header at all. */
    MISSING_SIGNATURE("missing_signature"),
    /** No signature matched any secret. */
    INVALID_SIGNATURE("invalid_signature"),
    /** The body has no integer {@code timestamp}. */
    MISSING_TIMESTAMP("missing_timestamp"),
    /** {@code X-Timestamp} differs from the body's {@code timestamp}. */
    TIMESTAMP_MISMATCH("timestamp_mismatch"),
    /** The timestamp is further from now than the tolerance. */
    TIMESTAMP_OUT_OF_TOLERANCE("timestamp_out_of_tolerance"),
    /** The body is not a UTF-8 JSON object Didit could have sent. */
    INVALID_PAYLOAD("invalid_payload");

    private final String code;

    Reason(String code) {
      this.code = code;
    }

    /**
     * The machine-readable code shared by every Didit SDK.
     *
     * @return for example {@code invalid_signature}
     */
    public String code() {
      return code;
    }
  }

  private final Reason reason;

  /**
   * Creates the exception.
   *
   * @param reason why the delivery was rejected
   * @param message a human-readable explanation
   */
  public WebhookVerificationException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  /**
   * Why the delivery was rejected.
   *
   * @return the reason
   */
  public Reason reason() {
    return reason;
  }
}
