package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import me.didit.core.Conv;

/** A user event */
public abstract class UserEvent extends WebhookEvent {
  UserEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }

  /**
   * Didit's id of {@code user.status.updated} or {@code user.data.updated}.:the user.
   *
   * @return the id
   */
  public final Optional<String> vendorUserId() {
    return optional("vendor_user_id", Conv.STRING);
  }

  /**
   * Your reference for {@code user.status.updated} or {@code user.data.updated}.:the user.
   *
   * @return the vendor data
   */
  public final Optional<String> vendorData() {
    return optional("vendor_data", Conv.STRING);
  }

  /**
   * The status of {@code user.status.updated} or {@code user.data.updated}.:the user.
   *
   * @return the status
   */
  public final Optional<String> status() {
    return optional("status", Conv.STRING);
  }
}
