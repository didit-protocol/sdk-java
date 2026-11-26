package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;
import me.didit.types.BankSyncCompletedData;

/**
 * {@code bank.sync.completed}: a sync finished. Off by default; subscribe only if you want to see
 * every sync, since {@code bank.data.updated} stays the default signal.
 */
public final class BankSyncCompletedEvent extends BankEvent {
  /** The {@code webhook_type} of this event. */
  public static final String TYPE = "bank.sync.completed";

  BankSyncCompletedEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }

  /**
   * The connection, sync run, trigger, outcome and whether changes were stored.
   *
   * @return the sync data
   */
  @Override
  public BankSyncCompletedData data() {
    return BankSyncCompletedData.fromJson(json);
  }
}
