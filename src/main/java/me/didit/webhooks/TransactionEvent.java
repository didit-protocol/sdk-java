package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import me.didit.core.Conv;
import me.didit.types.TransactionDetail;

/**
 * A transaction monitoring event: {@code transaction.created} or {@code
 * transaction.status.updated}.
 */
public abstract class TransactionEvent extends WebhookEvent {
  TransactionEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }

  /**
   * Didit's transaction id.
   *
   * @return the id
   */
  public final Optional<String> transactionId() {
    return optional("transaction_id", Conv.STRING);
  }

  /**
   * Your transaction reference.
   *
   * @return the reference
   */
  public final Optional<String> txnId() {
    return optional("txn_id", Conv.STRING);
  }

  /**
   * The transaction status.
   *
   * @return the status
   */
  public final Optional<String> status() {
    return optional("status", Conv.STRING);
  }

  /**
   * The risk score.
   *
   * @return the score
   */
  public final Optional<Double> score() {
    return optional("score", Conv.DOUBLE);
  }

  /**
   * The severity.
   *
   * @return the severity
   */
  public final Optional<String> severity() {
    return optional("severity", Conv.STRING);
  }

  /**
   * The amount, as a decimal string.
   *
   * @return the amount
   */
  public final Optional<String> amount() {
    return optional("amount", Conv.STRING);
  }

  /**
   * The currency.
   *
   * @return the currency
   */
  public final Optional<String> currency() {
    return optional("currency", Conv.STRING);
  }

  /**
   * {@code inbound} or {@code outbound}.
   *
   * @return the direction
   */
  public final Optional<String> direction() {
    return optional("direction", Conv.STRING);
  }

  /**
   * The action the subject must take, when one is required.
   *
   * @return the action
   */
  public final Optional<TransactionDetail.ActionRequired> actionRequired() {
    return optional("action_required", Conv.union(TransactionDetail.ActionRequired::fromJson));
  }
}
