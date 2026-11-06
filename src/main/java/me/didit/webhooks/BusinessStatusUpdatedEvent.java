package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** {@code business.status.updated}: a business's status changed. */
public final class BusinessStatusUpdatedEvent extends BusinessEvent {
  /** The {@code webhook_type} of this event. */
  public static final String TYPE = "business.status.updated";

  BusinessStatusUpdatedEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }
}
