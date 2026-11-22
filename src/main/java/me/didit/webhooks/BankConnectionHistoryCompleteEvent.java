package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;
import me.didit.types.BankConnectionHistoryCompleteData;

/**
 * {@code bank.connection.history_complete}: the history backfill of a connection ended.
 * {@code partial} means the bank could not deliver every requested range.
 */
public final class BankConnectionHistoryCompleteEvent extends BankEvent {
  /** The {@code webhook_type} of this event. */
  public static final String TYPE = "bank.connection.history_complete";

  BankConnectionHistoryCompleteEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }

  /**
   * The connection and the backfill status.
   *
   * @return the history data
   */
  @Override
  public BankConnectionHistoryCompleteData data() {
    return BankConnectionHistoryCompleteData.fromJson(json);
  }
}
