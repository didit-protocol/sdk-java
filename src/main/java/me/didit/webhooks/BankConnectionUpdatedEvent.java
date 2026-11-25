package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;
import me.didit.types.BankConnectionUpdatedData;

/**
 * {@code bank.connection.updated}: a connection's status changed in a way no other event reports. A
 * revocation starts with {@code revocation_status: pending} until the bank confirms it.
 */
public final class BankConnectionUpdatedEvent extends BankEvent {
  /** The {@code webhook_type} of this event. */
  public static final String TYPE = "bank.connection.updated";

  BankConnectionUpdatedEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }

  /**
   * The connection id and status, with a pending revocation or an update reason and end time.
   *
   * @return the connection data
   */
  @Override
  public BankConnectionUpdatedData data() {
    return BankConnectionUpdatedData.fromJson(json);
  }
}
