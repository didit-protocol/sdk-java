package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;
import me.didit.types.BankSyncRecoveredData;

/** {@code bank.sync.recovered}: syncs work again after {@code bank.sync.failed}. */
public final class BankSyncRecoveredEvent extends BankEvent {
  /** The {@code webhook_type} of this event. */
  public static final String TYPE = "bank.sync.recovered";

  BankSyncRecoveredEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }

  /**
   * The connection and the sync that succeeded.
   *
   * @return the sync data
   */
  @Override
  public BankSyncRecoveredData data() {
    return BankSyncRecoveredData.fromJson(json);
  }
}
