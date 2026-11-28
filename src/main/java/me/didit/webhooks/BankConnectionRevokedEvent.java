package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;
import me.didit.types.BankConnectionRevokedData;

/**
 * {@code bank.connection.revoked}: access ended. {@code ended_by} says whether the person, the
 * bank, the customer or Didit ({@code system}) ended it. With {@code ended_by: system}, {@code
 * status_reason} is {@code unpaid} when the coming month was not paid, or {@code
 * collection_complete} when a one-time read completed. {@code status_reason} is an open enum, so
 * {@code unpaid} decodes even before this SDK version lists it.
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
