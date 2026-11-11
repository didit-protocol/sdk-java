package me.didit.authentication;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import me.didit.core.Conv;

/**
 * The result of {@link Authentication#submitAgentConsent}: an approval ({@link #code()}, {@link
 * #redirectUri()}, {@link #state()}) or a denial ({@link #isDenied()}). It carries no access or
 * refresh token. {@code toString()} never prints the code.
 */
public final class AgentConsent extends JsonView {
  AgentConsent(ObjectNode json) {
    super(json);
  }

  /**
   * Whether the person declined.
   *
   * @return true when the status is {@code denied}
   */
  public boolean isDenied() {
    return status().map("denied"::equals).orElse(false);
  }

  /**
   * Whether the person approved.
   *
   * @return true when an authorization code was issued
   */
  public boolean isApproved() {
    return code().isPresent();
  }

  /**
   * The denial status, {@code denied}; absent on an approval.
   *
   * @return the status
   */
  public Optional<String> status() {
    return optional("status", Conv.STRING);
  }

  /**
   * The single-use agent authorization code, valid for 60 seconds. Hand it to the agent; never log
   * it.
   *
   * @return the code, on an approval
   */
  public Optional<String> code() {
    return optional("code", Conv.STRING);
  }

  /**
   * The registered redirect URI from the agent request.
   *
   * @return the URI, on an approval
   */
  public Optional<String> redirectUri() {
    return optional("redirect_uri", Conv.STRING);
  }

  /**
   * The state from the agent request.
   *
   * @return the state, on an approval
   */
  public Optional<String> state() {
    return optional("state", Conv.STRING);
  }
}
