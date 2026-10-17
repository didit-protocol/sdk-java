package me.didit.webhooks;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Options of {@link Webhooks#verify}: the secrets, the timestamp tolerance and whether the
 * deprecated Simple signature is accepted.
 *
 * <pre>{@code
 * VerifyOptions options = VerifyOptions.builder()
 *     .secret(System.getenv("DIDIT_WEBHOOK_SECRET"))
 *     .secret(System.getenv("DIDIT_WEBHOOK_SECRET_PREVIOUS")) // during a rotation
 *     .build();
 * }</pre>
 */
public final class VerifyOptions {
  /** The default tolerance between the delivery timestamp and now. */
  public static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);

  private final List<String> secrets;
  private final Duration tolerance;
  private final boolean allowSimple;
  private final Clock clock;

  private VerifyOptions(Builder b) {
    this.secrets = List.copyOf(b.secrets);
    this.tolerance = b.tolerance;
    this.allowSimple = b.allowSimple;
    this.clock = b.clock;
  }

  /**
   * Options with one secret and the defaults.
   *
   * @param secret the endpoint's webhook secret
   * @return the options
   */
  public static VerifyOptions of(String secret) {
    return builder().secret(secret).build();
  }

  /**
   * Returns an empty builder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  List<String> secrets() {
    return secrets;
  }

  Duration tolerance() {
    return tolerance;
  }

  boolean allowSimple() {
    return allowSimple;
  }

  Clock clock() {
    return clock;
  }

  @Override
  public String toString() {
    return "VerifyOptions{secrets="
        + secrets.size()
        + ", tolerance="
        + tolerance
        + ", allowSimple="
        + allowSimple
        + "}";
  }

  /** Builds {@link VerifyOptions}. */
  public static final class Builder {
    private final List<String> secrets = new ArrayList<>();
    private Duration tolerance = DEFAULT_TOLERANCE;
    private boolean allowSimple;
    private Clock clock = Clock.systemUTC();

    private Builder() {}

    /**
     * Adds a secret. Add the current and the previous secret during a rotation.
     *
     * @param secret a webhook secret
     * @return this builder
     */
    public Builder secret(String secret) {
      secrets.add(Objects.requireNonNull(secret, "secret"));
      return this;
    }

    /**
     * Adds several secrets.
     *
     * @param secrets webhook secrets
     * @return this builder
     */
    public Builder secrets(List<String> secrets) {
      for (String secret : secrets) {
        secret(secret);
      }
      return this;
    }

    /**
     * The largest accepted difference between the delivery timestamp and now. Defaults to 300
     * seconds.
     *
     * @param tolerance zero or more
     * @return this builder
     */
    public Builder tolerance(Duration tolerance) {
      Objects.requireNonNull(tolerance, "tolerance");
      if (tolerance.isNegative()) {
        throw new IllegalArgumentException("tolerance must not be negative");
      }
      this.tolerance = tolerance;
      return this;
    }

    /**
     * Accepts the deprecated {@code X-Signature-Simple}, which authenticates the envelope only. Off
     * by default.
     *
     * @param allowSimple whether to accept it
     * @return this builder
     */
    public Builder allowSimple(boolean allowSimple) {
      this.allowSimple = allowSimple;
      return this;
    }

    /**
     * The clock that gives "now", for tests.
     *
     * @param clock the clock
     * @return this builder
     */
    public Builder clock(Clock clock) {
      this.clock = Objects.requireNonNull(clock, "clock");
      return this;
    }

    /**
     * Builds the options.
     *
     * @return the options
     */
    public VerifyOptions build() {
      return new VerifyOptions(this);
    }
  }
}
