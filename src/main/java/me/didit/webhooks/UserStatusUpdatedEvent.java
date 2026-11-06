package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** {@code user.status.updated}: a user's status changed. */
public final class UserStatusUpdatedEvent extends UserEvent {
  /** The {@code webhook_type} of this event. */
  public static final String TYPE = "user.status.updated";

  UserStatusUpdatedEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }
}
