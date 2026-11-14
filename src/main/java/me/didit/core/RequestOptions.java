package me.didit.core;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import me.didit.errors.ConfigurationException;
import org.jspecify.annotations.Nullable;

/**
 * Options for one request. Anything set here overrides the client options for that request only.
 *
 * <pre>{@code
 * client.sessions().create(params, RequestOptions.builder()
 *     .timeout(Duration.ofSeconds(10))
 *     .idempotencyKey("order-123")
 *     .build());
 * }</pre>
 */
public final class RequestOptions {
  private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;
  private static final RequestOptions NONE = builder().build();

  private final @Nullable String apiKey;
  private final @Nullable String accountToken;
  private final @Nullable String organizationId;
  private final @Nullable String applicationId;
  private final @Nullable Duration timeout;
  private final @Nullable Integer maxRetries;
  private final @Nullable String idempotencyKey;
  private final Map<String, String> headers;
  private final Map<String, String> queryParams;

  private RequestOptions(Builder builder) {
    this.apiKey = builder.apiKey;
    this.accountToken = builder.accountToken;
    this.organizationId = builder.organizationId;
    this.applicationId = builder.applicationId;
    this.timeout = builder.timeout;
    this.maxRetries = builder.maxRetries;
    this.idempotencyKey = builder.idempotencyKey;
    this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(builder.headers));
    this.queryParams = Collections.unmodifiableMap(new LinkedHashMap<>(builder.queryParams));
  }

  /**
   * No overrides.
   *
   * @return options that change nothing
   */
  public static RequestOptions none() {
    return NONE;
  }

  /**
   * Returns an empty builder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  Optional<String> apiKey() {
    return Optional.ofNullable(apiKey);
  }

  Optional<String> accountToken() {
    return Optional.ofNullable(accountToken);
  }

  Optional<String> organizationId() {
    return Optional.ofNullable(organizationId);
  }

  Optional<String> applicationId() {
    return Optional.ofNullable(applicationId);
  }

  Optional<Duration> timeout() {
    return Optional.ofNullable(timeout);
  }

  Optional<Integer> maxRetries() {
    return Optional.ofNullable(maxRetries);
  }

  /**
   * The {@code Idempotency-Key} header for this request.
   *
   * @return the key, if set
   */
  public Optional<String> idempotencyKey() {
    return Optional.ofNullable(idempotencyKey);
  }

  /**
   * Extra headers for this request.
   *
   * @return header names to values
   */
  public Map<String, String> headers() {
    return headers;
  }

  /**
   * Extra query parameters for this request.
   *
   * @return parameter names to values
   */
  public Map<String, String> queryParams() {
    return queryParams;
  }

  @Override
  public String toString() {
    return "RequestOptions{timeout="
        + timeout
        + ", maxRetries="
        + maxRetries
        + ", organizationId="
        + organizationId
        + ", applicationId="
        + applicationId
        + ", headers="
        + headers.keySet()
        + "}";
  }

  /** Builds {@link RequestOptions}. */
  public static final class Builder {
    private @Nullable String apiKey;
    private @Nullable String accountToken;
    private @Nullable String organizationId;
    private @Nullable String applicationId;
    private @Nullable Duration timeout;
    private @Nullable Integer maxRetries;
    private @Nullable String idempotencyKey;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private final Map<String, String> queryParams = new LinkedHashMap<>();

    private Builder() {}

    /**
     * Uses another API key for this request.
     *
     * @param apiKey the API key
     * @return this builder
     */
    public Builder apiKey(String apiKey) {
      this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
      return this;
    }

    /**
     * Uses another account token for this request ({@code account} operations).
     *
     * @param accountToken the Bearer token from {@code account().login(...)}
     * @return this builder
     */
    public Builder accountToken(String accountToken) {
      this.accountToken = Objects.requireNonNull(accountToken, "accountToken");
      return this;
    }

    /**
     * Uses another organization id for this request (application-scoped operations).
     *
     * @param organizationId the organization id
     * @return this builder
     */
    public Builder organizationId(String organizationId) {
      this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
      return this;
    }

    /**
     * Uses another application id for this request (application-scoped operations).
     *
     * @param applicationId the application id
     * @return this builder
     */
    public Builder applicationId(String applicationId) {
      this.applicationId = Objects.requireNonNull(applicationId, "applicationId");
      return this;
    }

    /**
     * The timeout of each attempt.
     *
     * @param timeout a positive duration
     * @return this builder
     */
    public Builder timeout(Duration timeout) {
      this.timeout = ClientOptions.positive(timeout, "timeout");
      return this;
    }

    /**
     * How many times to retry a failed request.
     *
     * @param maxRetries zero or more
     * @return this builder
     */
    public Builder maxRetries(int maxRetries) {
      this.maxRetries = ClientOptions.nonNegative(maxRetries, "maxRetries");
      return this;
    }

    /**
     * Sends {@code Idempotency-Key} on this request ({@code POST}, {@code PUT}, {@code PATCH} and
     * {@code DELETE}; it is left out of {@code GET} and {@code HEAD}). With a key, {@code POST} and
     * {@code PATCH} are retried like {@code GET}, sending the same key on every attempt. The SDK
     * never invents a key: choose one per operation and pass the same key when you repeat it.
     *
     * @param idempotencyKey a key of 1 to 255 characters, unique for this logical request
     * @return this builder
     * @throws ConfigurationException when the key is null, empty or longer than 255 characters
     */
    public Builder idempotencyKey(String idempotencyKey) {
      if (idempotencyKey == null
          || idempotencyKey.isEmpty()
          || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
        throw new ConfigurationException(
            "idempotencyKey must be a string of 1 to "
                + MAX_IDEMPOTENCY_KEY_LENGTH
                + " characters");
      }
      this.idempotencyKey = idempotencyKey;
      return this;
    }

    /**
     * Adds a header to this request.
     *
     * @param name the header name
     * @param value the header value
     * @return this builder
     */
    public Builder header(String name, String value) {
      headers.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
      return this;
    }

    /**
     * Adds a query parameter to this request.
     *
     * @param name the parameter name
     * @param value the parameter value
     * @return this builder
     */
    public Builder queryParam(String name, String value) {
      queryParams.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
      return this;
    }

    /**
     * Builds the options.
     *
     * @return the options
     */
    public RequestOptions build() {
      return new RequestOptions(this);
    }
  }
}
