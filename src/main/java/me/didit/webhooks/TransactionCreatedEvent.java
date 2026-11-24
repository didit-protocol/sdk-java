package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** {@code transaction.created}: a transaction was screened. */
public final class TransactionCreatedEvent extends TransactionEvent {
  /** The {@code webhook_type} of this event. */
  public static final String TYPE = "transaction.created";

  TransactionCreatedEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }
}
