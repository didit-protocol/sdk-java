package me.didit.authentication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import me.didit.core.Conv;

/** A live session, from {@link Authentication#getSession}. */
public final class AuthenticationSession extends JsonView {
  AuthenticationSession(ObjectNode json) {
    super(json);
  }

  /**
   * The identity id ({@code sub}).
   *
   * @return the subject
   */
  public Optional<String> subject() {
    return optional("sub", Conv.STRING);
  }

  /**
   * The session id ({@code sid}).
   *
   * @return the session id
   */
  public Optional<String> sessionId() {
    return optional("sid", Conv.STRING);
  }

  /**
   * The application id.
   *
   * @return the application id
   */
  public Optional<String> applicationId() {
    return optional("application_id", Conv.STRING);
  }

  /**
   * Your reference for the user.
   *
   * @return the vendor data
   */
  public Optional<String> vendorData() {
    return optional("vendor_data", Conv.STRING);
  }

  /**
   * How the user signed in.
   *
   * @return the methods
   */
  public List<String> authenticationMethods() {
    return optional("amr", Conv.list(Conv.STRING)).orElse(List.of());
  }

  /**
   * Whether the session passed multi-factor authentication.
   *
   * @return true after MFA
   */
  public boolean mfaVerified() {
    return optional("mfa_verified", Conv.BOOLEAN).orElse(false);
  }

  /**
   * The email address.
   *
   * @return the email
   */
  public Optional<String> email() {
    return optional("email", Conv.STRING);
  }

  /**
   * Whether the email address is verified.
   *
   * @return true when verified
   */
  public boolean emailVerified() {
    return optional("email_verified", Conv.BOOLEAN).orElse(false);
  }

  /**
   * The phone number.
   *
   * @return the phone
   */
  public Optional<String> phone() {
    return optional("phone", Conv.STRING);
  }

  /**
   * The verified claims; empty when the user has none.
   *
   * @return the claims
   */
  public Optional<VerifiedClaims> verified() {
    JsonNode value = json.get("verified");
    return value != null && value.isObject() && value.size() > 0
        ? Optional.of(new VerifiedClaims(((ObjectNode) value).deepCopy()))
        : Optional.empty();
  }

  /**
   * The verified identity record, as sent.
   *
   * @return the record
   */
  public Optional<JsonNode> verifiedIdentity() {
    return optional("verified_identity", Conv.ANY);
  }

  /**
   * When the session expires.
   *
   * @return the time
   */
  public Optional<OffsetDateTime> expiresAt() {
    return optional("expires_at", Conv.DATE_TIME);
  }

  /**
   * Where the hosted sign-out page returns to.
   *
   * @return the URL
   */
  public Optional<String> postLogoutRedirectUri() {
    return optional("post_logout_redirect_uri", Conv.STRING);
  }

  /**
   * The user's organizations; empty when organizations are off.
   *
   * @return the organizations
   */
  public List<Organization> organizations() {
    JsonNode value = json.get("organizations");
    if (value == null || !value.isArray()) {
      return List.of();
    }
    List<Organization> out = new ArrayList<>();
    for (JsonNode item : value) {
      if (item.isObject()) {
        out.add(new Organization(((ObjectNode) item).deepCopy()));
      }
    }
    return Collections.unmodifiableList(out);
  }

  /** One of the user's organizations. */
  public static final class Organization extends JsonView {
    Organization(ObjectNode json) {
      super(json);
    }

    /**
     * The organization id.
     *
     * @return the id
     */
    public Optional<String> organizationId() {
      return optional("organization_id", Conv.STRING);
    }

    /**
     * The organization name (sent as {@code organization__name}).
     *
     * @return the name
     */
    public Optional<String> organizationName() {
      return optional("organization__name", Conv.STRING);
    }

    /**
     * The user's role.
     *
     * @return the role
     */
    public Optional<String> role() {
      return optional("role", Conv.STRING);
    }

    /**
     * The custom role, when one is assigned: {@code key} and {@code permissions}.
     *
     * @return the custom role
     */
    public Optional<JsonNode> customRole() {
      return optional("custom_role", Conv.ANY);
    }
  }
}
