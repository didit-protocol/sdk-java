package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** {@code data.updated}: the data of a verification session changed. */
public final class SessionDataUpdatedEvent extends SessionEvent {
  /** The {@code webhook_type} of this event. */
  public static final String TYPE = "data.updated";

  SessionDataUpdatedEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }
}
