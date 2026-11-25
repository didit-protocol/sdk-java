package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * {@code bank.link.cancelled}: a Bank Link was cancelled, by the person or by {@code cancel_link}.
 */
public final class BankLinkCancelledEvent extends BankLinkEvent {
  /** The {@code webhook_type} of this event. */
  public static final String TYPE = "bank.link.cancelled";

  BankLinkCancelledEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }
}
