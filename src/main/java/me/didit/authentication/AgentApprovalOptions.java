package me.didit.authentication;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * WebAuthn request options for the approving person, from {@link
 * Authentication#createAgentApprovalOptions}. Pass them as {@code publicKey} to {@code
 * navigator.credentials.get()} in the browser after decoding the base64url fields.
 */
public final class AgentApprovalOptions extends JsonView {
  AgentApprovalOptions(ObjectNode json) {
    super(json);
  }

  /**
   * The WebAuthn options object.
   *
   * @return a copy of {@code options}
   */
  public Optional<ObjectNode> options() {
    return field("options").filter(n -> n.isObject()).map(n -> ((ObjectNode) n).deepCopy());
  }

  /**
   * The base64url WebAuthn challenge, valid for less than 300 seconds.
   *
   * @return the challenge
   */
  public Optional<String> webAuthnChallenge() {
    return options().flatMap(o -> Optional.ofNullable(o.get("challenge")).map(n -> n.asText()));
  }

  /**
   * The relying-party hostname.
   *
   * @return the host
   */
  public Optional<String> rpId() {
    return options().flatMap(o -> Optional.ofNullable(o.get("rpId")).map(n -> n.asText()));
  }

  /**
   * The ids of the approving person's enrolled passkeys, base64url.
   *
   * @return the credential ids
   */
  public List<String> allowedCredentialIds() {
    List<String> out = new ArrayList<>();
    options()
        .map(o -> o.get("allowCredentials"))
        .ifPresent(a -> a.forEach(c -> out.add(c.path("id").asText())));
    return out;
  }
}
