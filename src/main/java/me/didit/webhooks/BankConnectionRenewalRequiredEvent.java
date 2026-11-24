package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;
import me.didit.types.BankConnectionRenewalRequiredData;

/**
 * {@code bank.connection.renewal_required}: the consent ended or the bank asks the person to sign
 * in again. Data stays readable as last synced until the connection is renewed.
 */
public final class BankConnectionRenewalRequiredEvent extends BankEvent {
  /** The {@code webhook_type} of this event. */
  public static final String TYPE = "bank.connection.renewal_required";

  BankConnectionRenewalRequiredEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }

  /**
   * The connection, the reason and the renewal deadlines.
   *
   * @return the renewal data
   */
  @Override
  public BankConnectionRenewalRequiredData data() {
    return BankConnectionRenewalRequiredData.fromJson(json);
  }
}
