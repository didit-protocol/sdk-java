package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * {@code bank.link.failed}: a Bank Link failed. {@code data.error_code} says why, for example
 * {@code duplicate_conflict}. Nothing was published or charged.
 */
public final class BankLinkFailedEvent extends BankLinkEvent {
  /** The {@code webhook_type} of this event. */
  public static final String TYPE = "bank.link.failed";

  BankLinkFailedEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }
}
