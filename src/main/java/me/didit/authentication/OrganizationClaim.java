package me.didit.authentication;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import me.didit.core.Conv;

/** The {@code org} claim: the active organization and the user's role. */
public final class OrganizationClaim extends JsonView {
  OrganizationClaim(ObjectNode json) {
    super(json);
  }

  /**
   * The organization id.
   *
   * @return the id
   */
  public Optional<String> id() {
    return optional("id", Conv.STRING);
  }

  /**
   * {@code admin}, {@code member} or {@code billing}.
   *
   * @return the role
   */
  public Optional<String> role() {
    return optional("role", Conv.STRING);
  }
}
