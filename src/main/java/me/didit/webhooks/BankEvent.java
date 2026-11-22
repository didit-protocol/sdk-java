package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;
import me.didit.core.JsonModel;

/**
 * A {@code bank.*} webhook event. The payload is a generated type from {@code me.didit.types},
 * read with {@link #data()}.
 */
public abstract class BankEvent extends WebhookEvent {
  BankEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }

  /**
   * The event's data: ids and statuses, never personal data.
   *
   * @return the generated payload type of the event
   */
  public abstract JsonModel data();
}
