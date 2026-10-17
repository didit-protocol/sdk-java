package me.didit.authentication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import me.didit.core.Conv;

/** The claims every Didit Authentication token carries. */
public abstract class TokenClaims extends JsonView {
  TokenClaims(ObjectNode json) {
    super(json);
  }

  /**
   * {@code iss}: the application's issuer.
   *
   * @return the issuer
   */
  public final String issuer() {
    return optional("iss", Conv.STRING).orElse("");
  }

  /**
   * {@code sub}: the identity id, stable per user of the application.
   *
   * @return the subject
   */
  public final String subject() {
    return optional("sub", Conv.STRING).orElse("");
  }

  /**
   * {@code aud}: the audiences, the application id today.
   *
   * @return the audiences
   */
  public final List<String> audience() {
    return audiences(json);
  }

  static List<String> audiences(ObjectNode json) {
    JsonNode aud = json.get("aud");
    if (aud == null || aud.isNull()) {
      return List.of();
    }
    if (aud.isTextual()) {
      return List.of(aud.textValue());
    }
    List<String> out = new ArrayList<>();
    if (aud.isArray()) {
      for (JsonNode item : aud) {
        if (item.isTextual()) {
          out.add(item.textValue());
        }
      }
    }
    return Collections.unmodifiableList(out);
  }

  /**
   * {@code sid}: the session id; use it with {@link Authentication#getSession}.
   *
   * @return the session id
   */
  public final Optional<String> sessionId() {
    return optional("sid", Conv.STRING);
  }

  /**
   * {@code jti}: the unique token id.
   *
   * @return the token id
   */
  public final Optional<String> tokenId() {
    return optional("jti", Conv.STRING);
  }

  /**
   * {@code iat}: when the token was issued.
   *
   * @return the time
   */
  public final Instant issuedAt() {
    return instant("iat").orElse(Instant.EPOCH);
  }

  /**
   * {@code exp}: when the token expires.
   *
   * @return the time
   */
  public final Instant expiresAt() {
    return instant("exp").orElse(Instant.EPOCH);
  }

  /**
   * {@code nbf}: when the token becomes valid, if the token says.
   *
   * @return the time
   */
  public final Optional<Instant> notBefore() {
    return instant("nbf");
  }

  final Optional<Instant> instant(String claim) {
    return optional(claim, Conv.DOUBLE).map(seconds -> Instant.ofEpochMilli((long) (seconds * 1000)));
  }

  /**
   * {@code vendor_data}: your reference for the user.
   *
   * @return the vendor data
   */
  public final Optional<String> vendorData() {
    return optional("vendor_data", Conv.STRING);
  }

  /**
   * {@code amr}: how the user signed in, for example {@code passkey}.
   *
   * @return the methods
   */
  public final List<String> authenticationMethods() {
    return optional("amr", Conv.list(Conv.STRING)).orElse(List.of());
  }

  /**
   * {@code org}: the active organization and the user's role in it.
   *
   * @return the organization
   */
  public final Optional<OrganizationClaim> organization() {
    return objectClaim("org").map(OrganizationClaim::new);
  }

  /**
   * {@code verified}: the verified identity claims; empty when the user has none.
   *
   * @return the verified claims
   */
  public final Optional<VerifiedClaims> verified() {
    return objectClaim("verified").filter(o -> o.size() > 0).map(VerifiedClaims::new);
  }

  /**
   * {@code act}: the agent acting for the user, for agent tokens.
   *
   * @return the actor
   */
  public final Optional<ActorClaim> actor() {
    return objectClaim("act").map(ActorClaim::new);
  }

  private Optional<ObjectNode> objectClaim(String name) {
    JsonNode value = json.get(name);
    return value != null && value.isObject()
        ? Optional.of(((ObjectNode) value).deepCopy())
        : Optional.empty();
  }
}
