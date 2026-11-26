package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;
import me.didit.types.BankSyncFailedData;

/**
 * {@code bank.sync.failed}: syncs have been failing for 24 hours. The connection stays active while
 * they are retried.
 */
public final class BankSyncFailedEvent extends BankEvent {
  /** The {@code webhook_type} of this event. */
  public static final String TYPE = "bank.sync.failed";

  BankSyncFailedEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }

  /**
   * The connection, the latest failed sync, its error and when the failures started.
   *
   * @return the sync data
   */
  @Override
  public BankSyncFailedData data() {
    return BankSyncFailedData.fromJson(json);
  }
}
