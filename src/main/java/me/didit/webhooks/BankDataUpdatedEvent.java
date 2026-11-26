package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;
import me.didit.types.BankDataUpdatedData;

/**
 * {@code bank.data.updated}: a sync stored changes, or the first read of a connection published its
 * accounts. Call the change feed to read them; nothing is pushed.
 */
public final class BankDataUpdatedEvent extends BankEvent {
  /** The {@code webhook_type} of this event. */
  public static final String TYPE = "bank.data.updated";

  BankDataUpdatedEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }

  /**
   * The connection, sync run, trigger and per-account change counts.
   *
   * @return the change data
   */
  @Override
  public BankDataUpdatedData data() {
    return BankDataUpdatedData.fromJson(json);
  }
}
