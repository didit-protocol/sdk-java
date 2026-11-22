package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;
import me.didit.types.BankConnectionRenewedData;

/**
 * {@code bank.connection.renewed}: a renew link completed. The connection and account ids are
 * unchanged.
 */
public final class BankConnectionRenewedEvent extends BankEvent {
  /** The {@code webhook_type} of this event. */
  public static final String TYPE = "bank.connection.renewed";

  BankConnectionRenewedEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }

  /**
   * The connection, status, consent version and the renewed consent's end.
   *
   * @return the renewal data
   */
  @Override
  public BankConnectionRenewedData data() {
    return BankConnectionRenewedData.fromJson(json);
  }
}
