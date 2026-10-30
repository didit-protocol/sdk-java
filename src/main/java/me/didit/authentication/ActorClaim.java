package me.didit.authentication;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Optional;
import me.didit.core.Conv;

/** The {@code act} claim: the agent acting for the user. */
public final class ActorClaim extends JsonView {
  ActorClaim(ObjectNode json) {
    super(json);
  }

  /**
   * The agent id.
   *
   * @return the id
   */
  public Optional<String> subject() {
    return optional("sub", Conv.STRING);
  }

  /**
   * The agent's name.
   *
   * @return the name
   */
  public Optional<String> agent() {
    return optional("agent", Conv.STRING);
  }

  /**
   * What the agent may do.
   *
   * @return the scopes
   */
  public List<String> scopes() {
    return optional("scopes", Conv.list(Conv.STRING)).orElse(List.of());
  }
}
