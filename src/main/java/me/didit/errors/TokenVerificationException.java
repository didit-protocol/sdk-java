package me.didit.errors;

/**
 * An Authentication token failed verification (DESIGN 10). Treat the request as unauthenticated;
 * {@link #reason()} says why.
 */
public class TokenVerificationException extends DiditException {
  private static final long serialVersionUID = 1L;

  /** Why a token was rejected. */
  public enum Reason {
    /** Not a well-formed JWT. */
    MALFORMED("malformed"),
    /** The algorithm is not ES256. */
    UNSUPPORTED_ALGORITHM("unsupported_algorithm"),
    /** The {@code typ} header does not match the token kind. */
    INVALID_TYPE("invalid_type"),
    /** No published key has the token's {@code kid}. */
    UNKNOWN_KEY("unknown_key"),
    /** The signature does not match. */
    INVALID_SIGNATURE("invalid_signature"),
    /** {@code iss} is not the issuer. */
    INVALID_ISSUER("invalid_issuer"),
    /** {@code aud} does not contain the expected audience. */
    INVALID_AUDIENCE("invalid_audience"),
    /** {@code exp} has passed. */
    EXPIRED("expired"),
    /** {@code nbf} is in the future. */
    NOT_YET_VALID("not_yet_valid"),
    /** {@code iat} is in the future. */
    INVALID_ISSUED_AT("invalid_issued_at"),
    /** {@code nonce} differs from the one sent with the sign-in. */
    INVALID_NONCE("invalid_nonce"),
    /** A required claim is missing. */
    MISSING_CLAIM("missing_claim");

    private final String code;

    Reason(String code) {
      this.code = code;
    }

    /**
     * The machine-readable code shared by every Didit SDK.
     *
     * @return for example {@code expired}
     */
    public String code() {
      return code;
    }
  }

  private final Reason reason;

  /**
   * Creates the exception.
   *
   * @param reason why the token was rejected
   * @param message a human-readable explanation; never contains the token
   */
  public TokenVerificationException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  /**
   * Why the token was rejected.
   *
   * @return the reason
   */
  public Reason reason() {
    return reason;
  }
}
