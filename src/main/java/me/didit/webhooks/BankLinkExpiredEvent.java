package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** {@code bank.link.expired}: a Bank Link expired before it was completed. */
public final class BankLinkExpiredEvent extends BankLinkEvent {
  /** The {@code webhook_type} of this event. */
  public static final String TYPE = "bank.link.expired";

  BankLinkExpiredEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }
}
