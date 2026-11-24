package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import me.didit.core.Conv;

/** {@code travel_rule.status.updated}: the Travel Rule status of a transaction changed. */
public final class TravelRuleStatusUpdatedEvent extends WebhookEvent {
  /** The {@code webhook_type} of this event. */
  public static final String TYPE = "travel_rule.status.updated";

  TravelRuleStatusUpdatedEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }

  /**
   * Didit's transaction id.
   *
   * @return the id
   */
  public Optional<String> transactionId() {
    return optional("transaction_id", Conv.STRING);
  }

  /**
   * Your transaction reference.
   *
   * @return the reference
   */
  public Optional<String> txnId() {
    return optional("txn_id", Conv.STRING);
  }

  /**
   * The Travel Rule status.
   *
   * @return the status
   */
  public Optional<String> travelRuleStatus() {
    return optional("travel_rule_status", Conv.STRING);
  }

  /**
   * The Travel Rule rail, for example a protocol name.
   *
   * @return the rail
   */
  public Optional<String> rail() {
    return optional("rail", Conv.STRING);
  }

  /**
   * {@code inbound} or {@code outbound}.
   *
   * @return the direction
   */
  public Optional<String> direction() {
    return optional("direction", Conv.STRING);
  }
}
