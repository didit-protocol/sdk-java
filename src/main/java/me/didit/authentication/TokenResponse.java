package me.didit.authentication;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import me.didit.core.Conv;

/**
 * The tokens from {@link Authentication#exchangeCode} or {@link Authentication#refresh}. {@link
 * #toString()} never prints them.
 */
public final class TokenResponse extends JsonView {
  TokenResponse(ObjectNode json) {
    super(json);
  }

  /**
   * The access token (an ES256 JWT, valid for up to 5 minutes).
   *
   * @return the access token
   */
  public String accessToken() {
    return optional("access_token", Conv.STRING).orElse("");
  }

  /**
   * The ID token; verify it with {@link Authentication#verifyIdToken}.
   *
   * @return the ID token
   */
  public Optional<String> idToken() {
    return optional("id_token", Conv.STRING);
  }

  /**
   * The refresh token. Each one works once: a refresh returns a new one, and reusing a consumed
   * refresh token revokes the session.
   *
   * @return the refresh token
   */
  public Optional<String> refreshToken() {
    return optional("refresh_token", Conv.STRING);
  }

  /**
   * {@code Bearer}.
   *
   * @return the token type
   */
  public Optional<String> tokenType() {
    return optional("token_type", Conv.STRING);
  }

  /**
   * Seconds until the access token expires.
   *
   * @return the lifetime
   */
  public Optional<Long> expiresIn() {
    return optional("expires_in", Conv.LONG);
  }

  /**
   * The session the tokens belong to.
   *
   * @return the session id
   */
  public Optional<String> sessionId() {
    return optional("session_id", Conv.STRING);
  }
}
