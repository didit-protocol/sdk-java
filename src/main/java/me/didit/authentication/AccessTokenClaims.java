package me.didit.authentication;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import me.didit.core.Conv;

/** The claims of a verified access token ({@code typ: at+jwt}). */
public final class AccessTokenClaims extends TokenClaims {
  AccessTokenClaims(ObjectNode json) {
    super(json);
  }

  /**
   * {@code client_id}: the application id.
   *
   * @return the client id
   */
  public Optional<String> clientId() {
    return optional("client_id", Conv.STRING);
  }
}
