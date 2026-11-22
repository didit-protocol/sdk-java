package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;
import me.didit.types.BankConnectionDeletedData;

/**
 * {@code bank.connection.deleted}: the stored data of a connection was deleted. {@code
 * erasure_status} is {@code erased}.
 */
public final class BankConnectionDeletedEvent extends BankEvent {
  /** The {@code webhook_type} of this event. */
  public static final String TYPE = "bank.connection.deleted";

  BankConnectionDeletedEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }

  /**
   * The connection, the erasure status, why it was deleted and when it finished.
   *
   * @return the deletion data
   */
  @Override
  public BankConnectionDeletedData data() {
    return BankConnectionDeletedData.fromJson(json);
  }
}
