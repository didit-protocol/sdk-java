package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** {@code transaction.status.updated}: a transaction changed status. */
public final class TransactionStatusUpdatedEvent extends TransactionEvent {
  /** The {@code webhook_type} of this event. */
  public static final String TYPE = "transaction.status.updated";

  TransactionStatusUpdatedEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }
}
