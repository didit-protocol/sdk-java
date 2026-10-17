package me.didit.core;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import me.didit.errors.ConfigurationException;
import org.jspecify.annotations.Nullable;

/**
 * The configuration of a client (DESIGN 6). Immutable; build it with {@link #builder()}.
 *
 * <p>Unset values fall back to environment variables: {@code DIDIT_API_KEY}, {@code
 * DIDIT_ORGANIZATION_ID}, {@code DIDIT_APPLICATION_ID} and {@code DIDIT_LOG=debug}.
 */
public final class ClientOptions {
  /** The verification API. */
  public static final String DEFAULT_BASE_URL = "https://verification.didit.me";

  /** The account management API. */
  public static final String DEFAULT_ACCOUNT_BASE_URL = "https://apx.didit.me/auth/v2";

  /** The default timeout of each attempt. */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

  /** The default number of retries. */
  public static final int DEFAULT_MAX_RETRIES = 2;

  private final @Nullable String apiKey;
  private final @Nullable String accountToken;
  private final URI baseUrl;
  private final URI accountBaseUrl;
  private final @Nullable String organizationId;
  private final @Nullable String applicationId;
  private final Duration timeout;
  private final int maxRetries;
  private final Map<String, String> defaultHeaders;
  private final HttpTransport transport;
  private final boolean debugLogging;
  private final Scheduler scheduler;

  private ClientOptions(Builder b, HttpTransport transport) {
    Function<String, @Nullable String> env = b.environment;
    this.apiKey = firstNonEmpty(b.apiKey, env.apply("DIDIT_API_KEY"));
    this.accountToken = firstNonEmpty(b.accountToken, null);
    this.organizationId = firstNonEmpty(b.organizationId, env.apply("DIDIT_ORGANIZATION_ID"));
    this.applicationId = firstNonEmpty(b.applicationId, env.apply("DIDIT_APPLICATION_ID"));
    this.baseUrl = baseUrl(b.baseUrl, "baseUrl");
    this.accountBaseUrl = baseUrl(b.accountBaseUrl, "accountBaseUrl");
    this.timeout = b.timeout;
    this.maxRetries = b.maxRetries;
    this.defaultHeaders = Collections.unmodifiableMap(new LinkedHashMap<>(b.defaultHeaders));
    this.transport = transport;
    String log = env.apply("DIDIT_LOG");
    this.debugLogging =
        b.debugLogging != null
            ? b.debugLogging
            : log != null && log.trim().toLowerCase(Locale.ROOT).equals("debug");
    this.scheduler = b.scheduler;
  }

  /**
   * Returns a builder with the defaults.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns a builder holding these options, to derive a variant.
   *
   * @return a new builder
   */
  public Builder toBuilder() {
    Builder b = new Builder();
    b.apiKey = apiKey;
    b.accountToken = accountToken;
    b.organizationId = organizationId;
    b.applicationId = applicationId;
    b.baseUrl = baseUrl.toString();
    b.accountBaseUrl = accountBaseUrl.toString();
    b.timeout = timeout;
    b.maxRetries = maxRetries;
    b.defaultHeaders.putAll(defaultHeaders);
    b.transport = transport;
    b.debugLogging = debugLogging;
    b.scheduler = scheduler;
    b.environment = name -> null;
    return b;
  }

  /**
   * The API key, sent as {@code x-api-key} to {@link #baseUrl()} only.
   *
   * @return the API key, if configured
   */
  public Optional<String> apiKey() {
    return Optional.ofNullable(apiKey);
  }

  /**
   * The account token, sent as a Bearer token to {@link #accountBaseUrl()} only.
   *
   * @return the account token, if configured
   */
  public Optional<String> accountToken() {
    return Optional.ofNullable(accountToken);
  }

  /**
   * The verification API base URL.
   *
   * @return the base URL
   */
  public URI baseUrl() {
    return baseUrl;
  }

  /**
   * The account management API base URL.
   *
   * @return the base URL
   */
  public URI accountBaseUrl() {
    return accountBaseUrl;
  }

  /**
   * The organization id used by application-scoped operations.
   *
   * @return the id, if configured
   */
  public Optional<String> organizationId() {
    return Optional.ofNullable(organizationId);
  }

  /**
   * The application id used by application-scoped operations.
   *
   * @return the id, if configured
   */
  public Optional<String> applicationId() {
    return Optional.ofNullable(applicationId);
  }

  /**
   * The timeout of each attempt.
   *
   * @return the timeout
   */
  public Duration timeout() {
    return timeout;
  }

  /**
   * How many times a failed request is retried.
   *
   * @return the number of retries
   */
  public int maxRetries() {
    return maxRetries;
  }

  /**
   * Headers sent with every request.
   *
   * @return header names to values
   */
  public Map<String, String> defaultHeaders() {
    return defaultHeaders;
  }

  /**
   * The HTTP transport.
   *
   * @return the transport
   */
  public HttpTransport transport() {
    return transport;
  }

  /**
   * Whether every request is logged (method, path, status, duration and request id; never bodies or
   * headers).
   *
   * @return true when debug logging is on
   */
  public boolean debugLogging() {
    return debugLogging;
  }

  Scheduler scheduler() {
    return scheduler;
  }

  @Override
  public String toString() {
    return "ClientOptions{apiKey="
        + (apiKey == null ? "unset" : "[REDACTED]")
        + ", accountToken="
        + (accountToken == null ? "unset" : "[REDACTED]")
        + ", baseUrl="
        + baseUrl
        + ", accountBaseUrl="
        + accountBaseUrl
        + ", organizationId="
        + organizationId
        + ", applicationId="
        + applicationId
        + ", timeout="
        + timeout
        + ", maxRetries="
        + maxRetries
        + ", defaultHeaders="
        + defaultHeaders.keySet()
        + "}";
  }

  private static @Nullable String firstNonEmpty(@Nullable String a, @Nullable String b) {
    if (a != null && !a.trim().isEmpty()) {
      return a.trim();
    }
    if (b != null && !b.trim().isEmpty()) {
      return b.trim();
    }
    return null;
  }

  private static URI baseUrl(String value, String option) {
    URI uri;
    try {
      uri = new URI(value.endsWith("/") ? value.substring(0, value.length() - 1) : value);
    } catch (URISyntaxException e) {
      throw new ConfigurationException(option + " is not a valid URL");
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (uri.getHost() == null || !(scheme.equals("https") || scheme.equals("http"))) {
      throw new ConfigurationException(option + " must be an absolute http(s) URL");
    }
    if (scheme.equals("http") && !isLoopback(uri.getHost())) {
      throw new ConfigurationException(
          option + " must use https; plain http is only allowed for localhost");
    }
    if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
      throw new ConfigurationException(option + " must not have a query or fragment");
    }
    return uri;
  }

  static boolean isLoopback(String host) {
    String h = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
    if (h.equalsIgnoreCase("localhost")) {
      return true;
    }
    if (!h.matches("[0-9.]+") && !h.contains(":")) {
      return false; // never resolve names here
    }
    try {
      return InetAddress.getByName(h).isLoopbackAddress();
    } catch (UnknownHostException e) {
      return false;
    }
  }

  static Duration positive(Duration value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isNegative() || value.isZero()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }

  static int nonNegative(int value, String name) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
    return value;
  }

  /** Delays retries; replaced in tests. */
  interface Scheduler {
    Executor after(Duration delay);

    Scheduler DEFAULT =
        delay -> CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS);
  }

  /** Builds {@link ClientOptions}. */
  public static final class Builder {
    private @Nullable String apiKey;
    private @Nullable String accountToken;
    private @Nullable String organizationId;
    private @Nullable String applicationId;
    private String baseUrl = DEFAULT_BASE_URL;
    private String accountBaseUrl = DEFAULT_ACCOUNT_BASE_URL;
    private Duration timeout = DEFAULT_TIMEOUT;
    private int maxRetries = DEFAULT_MAX_RETRIES;
    private final Map<String, String> defaultHeaders = new LinkedHashMap<>();
    private @Nullable HttpTransport transport;
    private @Nullable HttpClient httpClient;
    private @Nullable Boolean debugLogging;
    private Scheduler scheduler = Scheduler.DEFAULT;
    private Function<String, @Nullable String> environment = System::getenv;

    private Builder() {}

    /**
     * The API key. Defaults to {@code DIDIT_API_KEY}.
     *
     * @param apiKey the API key from the Business Console
     * @return this builder
     */
    public Builder apiKey(String apiKey) {
      this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
      return this;
    }

    /**
     * The account token for {@code account} operations.
     *
     * @param accountToken the Bearer token from {@code account().login(...)}
     * @return this builder
     */
    public Builder accountToken(String accountToken) {
      this.accountToken = Objects.requireNonNull(accountToken, "accountToken");
      return this;
    }

    /**
     * The organization id for application-scoped operations. Defaults to {@code
     * DIDIT_ORGANIZATION_ID}.
     *
     * @param organizationId the organization id
     * @return this builder
     */
    public Builder organizationId(String organizationId) {
      this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
      return this;
    }

    /**
     * The application id for application-scoped operations. Defaults to {@code
     * DIDIT_APPLICATION_ID}.
     *
     * @param applicationId the application id
     * @return this builder
     */
    public Builder applicationId(String applicationId) {
      this.applicationId = Objects.requireNonNull(applicationId, "applicationId");
      return this;
    }

    /**
     * The verification API base URL. Defaults to {@value ClientOptions#DEFAULT_BASE_URL}.
     *
     * @param baseUrl an https URL (http only for localhost)
     * @return this builder
     */
    public Builder baseUrl(String baseUrl) {
      this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
      return this;
    }

    /**
     * The account management API base URL. Defaults to {@value
     * ClientOptions#DEFAULT_ACCOUNT_BASE_URL}.
     *
     * @param accountBaseUrl an https URL (http only for localhost)
     * @return this builder
     */
    public Builder accountBaseUrl(String accountBaseUrl) {
      this.accountBaseUrl = Objects.requireNonNull(accountBaseUrl, "accountBaseUrl");
      return this;
    }

    /**
     * The timeout of each attempt. Defaults to 60 seconds.
     *
     * @param timeout a positive duration
     * @return this builder
     */
    public Builder timeout(Duration timeout) {
      this.timeout = positive(timeout, "timeout");
      return this;
    }

    /**
     * How many times a failed request is retried. Defaults to 2.
     *
     * @param maxRetries zero or more
     * @return this builder
     */
    public Builder maxRetries(int maxRetries) {
      this.maxRetries = nonNegative(maxRetries, "maxRetries");
      return this;
    }

    /**
     * Adds a header to every request.
     *
     * @param name the header name
     * @param value the header value
     * @return this builder
     */
    public Builder defaultHeader(String name, String value) {
      defaultHeaders.put(
          Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
      return this;
    }

    /**
     * Sends requests with this {@link HttpClient} (proxies, TLS, executor). It must not follow
     * redirects.
     *
     * @param httpClient the client
     * @return this builder
     */
    public Builder httpClient(HttpClient httpClient) {
      this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
      this.transport = null;
      return this;
    }

    /**
     * Sends requests through a custom transport, for example a stub in tests.
     *
     * @param transport the transport
     * @return this builder
     */
    public Builder transport(HttpTransport transport) {
      this.transport = Objects.requireNonNull(transport, "transport");
      this.httpClient = null;
      return this;
    }

    /**
     * Logs every request (method, path, status, duration, request id) to the {@code me.didit}
     * {@link System.Logger}. Defaults to {@code DIDIT_LOG=debug}.
     *
     * @param enabled whether to log
     * @return this builder
     */
    public Builder debugLogging(boolean enabled) {
      this.debugLogging = enabled;
      return this;
    }

    /**
     * Where the {@code DIDIT_*} fallbacks are read from. Defaults to {@link System#getenv}.
     *
     * @param environment variable name to value, or {@code null} when unset
     * @return this builder
     */
    public Builder environment(Function<String, @Nullable String> environment) {
      this.environment = Objects.requireNonNull(environment, "environment");
      return this;
    }

    Builder scheduler(Scheduler scheduler) {
      this.scheduler = scheduler;
      return this;
    }

    /**
     * Builds the options.
     *
     * @return the options
     * @throws ConfigurationException when a URL is invalid
     */
    public ClientOptions build() {
      HttpTransport chosen = transport;
      if (chosen == null) {
        chosen =
            httpClient != null
                ? new JdkHttpTransport(httpClient)
                : JdkHttpTransport.create(
                    timeout.compareTo(Duration.ofSeconds(30)) < 0
                        ? timeout
                        : Duration.ofSeconds(30));
      }
      return new ClientOptions(this, chosen);
    }
  }
}
