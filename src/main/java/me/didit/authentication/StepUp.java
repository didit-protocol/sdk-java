package me.didit.authentication;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.Optional;
import me.didit.core.Conv;

/**
 * A step-up verification. Act on a sensitive action only when {@link #isApproved()} is true, after
 * reading it back with {@link Authentication#retrieveStepUp}.
 */
public final class StepUp extends JsonView {
  StepUp(ObjectNode json) {
    super(json);
  }

  /**
   * Whether verification is needed. False when no rule applies: go ahead with the action.
   *
   * @return true when the user must verify
   */
  public boolean required() {
    return optional("required", Conv.BOOLEAN).orElse(true);
  }

  /**
   * The step-up id.
   *
   * @return the id
   */
  public Optional<String> id() {
    return optional("id", Conv.STRING);
  }

  /**
   * The rule that applied: {@code payout}, {@code bank}, {@code contact} or {@code device}.
   *
   * @return the rule
   */
  public Optional<String> rule() {
    return optional("rule", Conv.STRING);
  }

  /**
   * {@code liveness}, {@code selfie} or {@code full_id}.
   *
   * @return the method
   */
  public Optional<String> method() {
    return optional("method", Conv.STRING);
  }

  /**
   * {@code pending}, {@code review}, {@code approved}, {@code declined}, {@code expired} or {@code
   * superseded}.
   *
   * @return the status
   */
  public Optional<String> status() {
    return optional("status", Conv.STRING);
  }

  /**
   * Whether the step-up is approved.
   *
   * @return true only for status {@code approved}
   */
  public boolean isApproved() {
    return status().map("approved"::equals).orElse(false);
  }

  /**
   * The verification level.
   *
   * @return the level
   */
  public Optional<String> level() {
    return optional("level", Conv.STRING);
  }

  /**
   * The amount, as a decimal string.
   *
   * @return the amount
   */
  public Optional<String> amount() {
    return optional("amount", Conv.STRING);
  }

  /**
   * The currency.
   *
   * @return the currency
   */
  public Optional<String> currency() {
    return optional("currency", Conv.STRING);
  }

  /**
   * Where to send the user to verify.
   *
   * @return the URL
   */
  public Optional<String> verificationUrl() {
    return optional("verification_url", Conv.STRING);
  }

  /**
   * Seconds until the verification link expires.
   *
   * @return the lifetime
   */
  public Optional<Long> expiresIn() {
    return optional("expires_in", Conv.LONG);
  }

  /**
   * When the step-up was created.
   *
   * @return the time
   */
  public Optional<OffsetDateTime> createdAt() {
    return optional("created_at", Conv.DATE_TIME);
  }

  /**
   * When the step-up finished.
   *
   * @return the time
   */
  public Optional<OffsetDateTime> completedAt() {
    return optional("completed_at", Conv.DATE_TIME);
  }

  /**
   * When the step-up expires.
   *
   * @return the time
   */
  public Optional<OffsetDateTime> expiresAt() {
    return optional("expires_at", Conv.DATE_TIME);
  }
}
