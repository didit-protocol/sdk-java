package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** {@code status.updated}: a verification session changed status. */
public final class SessionStatusUpdatedEvent extends SessionEvent {
  /** The {@code webhook_type} of this event. */
  public static final String TYPE = "status.updated";

  SessionStatusUpdatedEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }
}
