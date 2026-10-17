package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * An event type this SDK version does not know. The payload is kept: read it with {@link
 * #payload()}.
 */
public final class UnknownEvent extends WebhookEvent {
  UnknownEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }

  /**
   * The whole payload.
   *
   * @return a copy of the JSON object
   */
  public ObjectNode payload() {
    return _json();
  }
}
