package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** {@code user.data.updated}: a user's data changed. */
public final class UserDataUpdatedEvent extends UserEvent {
  /** The {@code webhook_type} of this event. */
  public static final String TYPE = "user.data.updated";

  UserDataUpdatedEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }
}
