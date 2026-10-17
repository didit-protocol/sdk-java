package me.didit.authentication;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import me.didit.core.Conv;

/** The {@code verified} claim: what Didit verified about the user. Fields are present when shared. */
public final class VerifiedClaims extends JsonView {
  VerifiedClaims(ObjectNode json) {
    super(json);
  }

  /**
   * {@code liveness}, {@code liveness_face_match} or {@code full_id}.
   *
   * @return the level
   */
  public Optional<String> level() {
    return optional("level", Conv.STRING);
  }

  /**
   * Whether the user is at least 18.
   *
   * @return the answer, when shared
   */
  public Optional<Boolean> over18() {
    return optional("over_18", Conv.BOOLEAN);
  }

  /**
   * Whether the user is at least 21.
   *
   * @return the answer, when shared
   */
  public Optional<Boolean> over21() {
    return optional("over_21", Conv.BOOLEAN);
  }

  /**
   * The given name from the document.
   *
   * @return the name
   */
  public Optional<String> givenName() {
    return optional("given_name", Conv.STRING);
  }

  /**
   * The family name from the document.
   *
   * @return the name
   */
  public Optional<String> familyName() {
    return optional("family_name", Conv.STRING);
  }

  /**
   * The date of birth, as sent ({@code YYYY-MM-DD}).
   *
   * @return the date
   */
  public Optional<String> birthdate() {
    return optional("birthdate", Conv.STRING);
  }

  /**
   * The nationality.
   *
   * @return the country code
   */
  public Optional<String> nationality() {
    return optional("nationality", Conv.STRING);
  }

  /**
   * The country.
   *
   * @return the country code
   */
  public Optional<String> country() {
    return optional("country", Conv.STRING);
  }

  /**
   * Whether an identity document was verified.
   *
   * @return the answer, when shared
   */
  public Optional<Boolean> documentVerified() {
    return optional("document_verified", Conv.BOOLEAN);
  }

  /**
   * Whether a liveness check passed.
   *
   * @return the answer, when shared
   */
  public Optional<Boolean> liveness() {
    return optional("liveness", Conv.BOOLEAN);
  }
}
