package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;
import me.didit.types.BankConnectionConsentExpiringData;

/**
 * {@code bank.connection.consent_expiring}: the consent ends soon. Sent 14, 7 and 1 day before the
 * end; renew with {@code renew_connection}.
 */
public final class BankConnectionConsentExpiringEvent extends BankEvent {
  /** The {@code webhook_type} of this event. */
  public static final String TYPE = "bank.connection.consent_expiring";

  BankConnectionConsentExpiringEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }

  /**
   * The connection, the days left, the consent's end and when to renew by.
   *
   * @return the consent data
   */
  @Override
  public BankConnectionConsentExpiringData data() {
    return BankConnectionConsentExpiringData.fromJson(json);
  }
}
