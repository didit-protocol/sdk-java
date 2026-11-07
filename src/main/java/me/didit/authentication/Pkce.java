package me.didit.authentication;

/**
 * A PKCE pair (RFC 7636, S256). Keep the verifier on your server and send the challenge with the
 * sign-in request.
 */
public final class Pkce {
  private final String verifier;
  private final String challenge;

  Pkce(String verifier, String challenge) {
    this.verifier = verifier;
    this.challenge = challenge;
  }

  /**
   * The code verifier: 43 URL-safe characters. Secret.
   *
   * @return the verifier
   */
  public String verifier() {
    return verifier;
  }

  /**
   * The code challenge: base64url SHA-256 of the verifier.
   *
   * @return the challenge
   */
  public String challenge() {
    return challenge;
  }

  /**
   * {@code S256}.
   *
   * @return the challenge method
   */
  public String method() {
    return "S256";
  }

  @Override
  public String toString() {
    return "Pkce{challenge=" + challenge + ", verifier=[REDACTED]}";
  }
}
