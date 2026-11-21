package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** {@code activity.created}: reserved; the envelope only. */
public final class ActivityCreatedEvent extends WebhookEvent {
  /** The {@code webhook_type} of this event. */
  public static final String TYPE = "activity.created";

  ActivityCreatedEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }
}
