package me.didit.authentication;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * What a step-up protects: {@code action} ({@code payout}, {@code bank} or {@code contact}), the
 * amount for payouts, and where the user returns after verifying.
 *
 * <pre>{@code
 * StepUpParams.builder("payout").amount("250.00").currency("EUR")
 *     .returnTo("https://app.example.com/payouts").build();
 * }</pre>
 */
public final class StepUpParams {
  private static final Pattern AMOUNT = Pattern.compile("-?[0-9]+(\\.[0-9]+)?");

  private final String action;
  private final @Nullable String amount;
  private final @Nullable String currency;
  private final @Nullable String returnTo;

  private StepUpParams(Builder b) {
    this.action = b.action;
    this.amount = b.amount;
    this.currency = b.currency;
    this.returnTo = b.returnTo;
  }

  /**
   * Starts the parameters.
   *
   * @param action {@code payout}, {@code bank} or {@code contact}
   * @return a new builder
   */
  public static Builder builder(String action) {
    return new Builder(action);
  }

  String action() {
    return action;
  }

  Optional<String> amount() {
    return Optional.ofNullable(amount);
  }

  Optional<String> currency() {
    return Optional.ofNullable(currency);
  }

  Optional<String> returnTo() {
    return Optional.ofNullable(returnTo);
  }

  /** Builds {@link StepUpParams}. */
  public static final class Builder {
    private final String action;
    private @Nullable String amount;
    private @Nullable String currency;
    private @Nullable String returnTo;

    private Builder(String action) {
      this.action = Objects.requireNonNull(action, "action");
    }

    /**
     * The amount, required for {@code payout}.
     *
     * @param amount a decimal such as {@code 250.00}
     * @return this builder
     */
    public Builder amount(String amount) {
      if (!AMOUNT.matcher(amount).matches()) {
        throw new IllegalArgumentException("amount must be a decimal number such as 250.00");
      }
      this.amount = amount;
      return this;
    }

    /**
     * The amount, required for {@code payout}.
     *
     * @param amount the amount
     * @return this builder
     */
    public Builder amount(BigDecimal amount) {
      return amount(amount.toPlainString());
    }

    /**
     * The ISO 4217 currency, for example {@code EUR}.
     *
     * @param currency the currency
     * @return this builder
     */
    public Builder currency(String currency) {
      this.currency = Objects.requireNonNull(currency, "currency");
      return this;
    }

    /**
     * Where the user returns after verifying. Didit requires it today, and it must be one of the
     * application's registered redirect URIs.
     *
     * @param returnTo the URL
     * @return this builder
     */
    public Builder returnTo(String returnTo) {
      this.returnTo = Objects.requireNonNull(returnTo, "returnTo");
      return this;
    }

    /**
     * Builds the parameters.
     *
     * @return the parameters
     */
    public StepUpParams build() {
      return new StepUpParams(this);
    }
  }
}
