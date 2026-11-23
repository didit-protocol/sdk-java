package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * {@code bank.link.completed}: a Bank Link was completed. {@code data.connection_id} is set, and
 * {@code data.check_id} when the link asked for an ownership comparison.
 */
public final class BankLinkCompletedEvent extends BankLinkEvent {
  /** The {@code webhook_type} of this event. */
  public static final String TYPE = "bank.link.completed";

  BankLinkCompletedEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }
}
