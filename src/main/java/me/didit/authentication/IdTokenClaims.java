package me.didit.authentication;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Optional;
import me.didit.core.Conv;

/** The claims of a verified ID token ({@code typ: JWT}). */
public final class IdTokenClaims extends TokenClaims {
  IdTokenClaims(ObjectNode json) {
    super(json);
  }

  /**
   * {@code auth_time}: when the user signed in.
   *
   * @return the time
   */
  public Optional<Instant> authTime() {
    return instant("auth_time");
  }

  /**
   * {@code nonce}: present when the sign-in sent one; never after a refresh.
   *
   * @return the nonce
   */
  public Optional<String> nonce() {
    return optional("nonce", Conv.STRING);
  }

  /**
   * {@code email}.
   *
   * @return the email address
   */
  public Optional<String> email() {
    return optional("email", Conv.STRING);
  }

  /**
   * {@code email_verified}.
   *
   * @return whether the email address is verified
   */
  public boolean emailVerified() {
    return optional("email_verified", Conv.BOOLEAN).orElse(false);
  }

  /**
   * {@code name}: the display name.
   *
   * @return the name
   */
  public Optional<String> name() {
    return optional("name", Conv.STRING);
  }

  /**
   * {@code phone_number}.
   *
   * @return the phone number
   */
  public Optional<String> phoneNumber() {
    return optional("phone_number", Conv.STRING);
  }
}
