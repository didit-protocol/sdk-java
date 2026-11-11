package me.didit.authentication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import me.didit.core.Json;
import org.jspecify.annotations.Nullable;

/**
 * The person's decision on a pending agent request: the request {@code challenge}, {@code approve}
 * or {@code deny}, and for an approval the WebAuthn assertion the browser produced.
 *
 * <p>The {@code challenge} is the pending agent request secret. Never log it, and never log the
 * assertion.
 *
 * <pre>{@code
 * AgentConsentParams.approve(challenge).assertion(assertionJson).build();
 * AgentConsentParams.deny(challenge).build();
 * }</pre>
 */
public final class AgentConsentParams {
  private final String challenge;
  private final String decision;
  private final @Nullable JsonNode assertion;
  private final @Nullable List<String> scopes;
  private final @Nullable List<String> organizationIds;
  private final @Nullable Integer lifetime;

  private AgentConsentParams(Builder b) {
    this.challenge = b.challenge;
    this.decision = b.decision;
    this.assertion = b.assertion;
    this.scopes = b.scopes == null ? null : List.copyOf(b.scopes);
    this.organizationIds = b.organizationIds == null ? null : List.copyOf(b.organizationIds);
    this.lifetime = b.lifetime;
  }

  /**
   * Starts an approval.
   *
   * @param challenge the pending agent request secret
   * @return a new builder
   */
  public static Builder approve(String challenge) {
    return new Builder(challenge, "approve");
  }

  /**
   * Starts a denial. A denial needs no assertion.
   *
   * @param challenge the pending agent request secret
   * @return a new builder
   */
  public static Builder deny(String challenge) {
    return new Builder(challenge, "deny");
  }

  ObjectNode toJson() {
    ObjectNode body = Json.nodes().objectNode();
    body.put("challenge", challenge);
    body.put("decision", decision);
    if (assertion != null) {
      body.set("assertion", assertion.deepCopy());
    }
    if (scopes != null) {
      ArrayNode array = body.putArray("scopes");
      scopes.forEach(array::add);
    }
    if (organizationIds != null) {
      ArrayNode ids = body.putArray("organization_ids");
      organizationIds.forEach(ids::add);
    }
    if (lifetime != null) {
      body.put("lifetime", lifetime);
    }
    return body;
  }

  /** Builds {@link AgentConsentParams}. */
  public static final class Builder {
    private final String challenge;
    private final String decision;
    private @Nullable JsonNode assertion;
    private @Nullable List<String> scopes;
    private @Nullable List<String> organizationIds;
    private @Nullable Integer lifetime;

    private Builder(String challenge, String decision) {
      this.challenge = Objects.requireNonNull(challenge, "challenge");
      if (challenge.isEmpty() || challenge.length() > 128) {
        throw new IllegalArgumentException("challenge must be 1 to 128 characters");
      }
      this.decision = decision;
    }

    /**
     * The JSON-serialised result of {@code navigator.credentials.get()} (binary fields encoded as
     * base64url), as the browser sent it to your backend. Required for an approval when the
     * application demands a human passkey.
     *
     * @param assertion the assertion object
     * @return this builder
     */
    public Builder assertion(JsonNode assertion) {
      if (!Objects.requireNonNull(assertion, "assertion").isObject()) {
        throw new IllegalArgumentException("assertion must be a JSON object");
      }
      this.assertion = assertion;
      return this;
    }

    /**
     * Narrows the requested scopes; leave unset to grant all of them.
     *
     * @param scopes one or two of {@code read} and {@code act}
     * @return this builder
     */
    public Builder scopes(List<String> scopes) {
      if (scopes.isEmpty() || scopes.size() > 2) {
        throw new IllegalArgumentException("scopes takes one or two of read and act");
      }
      for (String scope : scopes) {
        if (!"read".equals(scope) && !"act".equals(scope)) {
          throw new IllegalArgumentException("a scope is read or act");
        }
      }
      this.scopes = new ArrayList<>(scopes);
      return this;
    }

    /**
     * The organizations the person shares with the agent; unset means none.
     *
     * @param organizationIds organization ids the person belongs to (at most 100)
     * @return this builder
     */
    public Builder organizationIds(List<String> organizationIds) {
      if (organizationIds.size() > 100) {
        throw new IllegalArgumentException("organizationIds takes at most 100 ids");
      }
      organizationIds.forEach(UUID::fromString);
      this.organizationIds = new ArrayList<>(organizationIds);
      return this;
    }

    /**
     * The grant lifetime in seconds, limited by application policy.
     *
     * @param lifetime {@code 86400}, {@code 604800} or {@code 2592000}
     * @return this builder
     */
    public Builder lifetime(int lifetime) {
      if (lifetime != 86400 && lifetime != 604800 && lifetime != 2592000) {
        throw new IllegalArgumentException("lifetime is 86400, 604800 or 2592000 seconds");
      }
      this.lifetime = lifetime;
      return this;
    }

    /**
     * Builds the parameters.
     *
     * @return the parameters
     */
    public AgentConsentParams build() {
      return new AgentConsentParams(this);
    }
  }
}
