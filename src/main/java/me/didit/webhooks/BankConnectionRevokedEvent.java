package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;
import me.didit.types.BankConnectionRevokedData;

/**
 * {@code bank.connection.revoked}: access ended and the bank confirmed it. {@code ended_by} says
 * whether the person, the bank or the customer ended it.
 */
public final class BankConnectionRevokedEvent extends BankEvent {
  /** The {@code webhook_type} of this event. */
  public static final String TYPE = "bank.connection.revoked";

  BankConnectionRevokedEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }

  /**
   * The connection, who ended access, why and how long the data stays readable.
   *
   * @return the revocation data
   */
  @Override
  public BankConnectionRevokedData data() {
    return BankConnectionRevokedData.fromJson(json);
  }
}
