package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** {@code business.data.updated}: a business's data changed. */
public final class BusinessDataUpdatedEvent extends BusinessEvent {
  /** The {@code webhook_type} of this event. */
  public static final String TYPE = "business.data.updated";

  BusinessDataUpdatedEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }
}
