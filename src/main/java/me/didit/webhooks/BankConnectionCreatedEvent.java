package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;
import me.didit.types.BankConnectionCreatedData;

/**
 * {@code bank.connection.created}: the first sync of a connection finished and its accounts are
 * readable. {@code initial_status} says whether it succeeded or was partial.
 */
public final class BankConnectionCreatedEvent extends BankEvent {
  /** The {@code webhook_type} of this event. */
  public static final String TYPE = "bank.connection.created";

  BankConnectionCreatedEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }

  /**
   * The connection id and the first sync's outcome.
   *
   * @return the connection data
   */
  @Override
  public BankConnectionCreatedData data() {
    return BankConnectionCreatedData.fromJson(json);
  }
}
