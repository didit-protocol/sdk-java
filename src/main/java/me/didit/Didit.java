package me.didit;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Function;
import me.didit.core.ApiClient;
import me.didit.core.ClientOptions;
import me.didit.core.HttpTransport;
import me.didit.resources.DiditResources;
import org.jspecify.annotations.Nullable;

/**
 * The Didit API client (DESIGN 6). Thread-safe and reusable: create one per process.
 *
 * <pre>{@code
 * Didit client = Didit.builder().apiKey(System.getenv("DIDIT_API_KEY")).build();
 * SessionsCreateResponse session = client.sessions().create(
 *     SessionsCreateParams.builder().workflowId(workflowId).vendorData("user-42").build());
 * }</pre>
 *
 * <p>Every resource method has an {@code ...Async} variant returning a {@link
 * java.util.concurrent.CompletableFuture}, and {@code withRawResponse()} gives the HTTP status,
 * headers and request id.
 */
public final class Didit extends DiditResources {
  private final ApiClient client;

  /**
   * A client configured from the environment: {@code DIDIT_API_KEY}, {@code DIDIT_ORGANIZATION_ID},
   * {@code DIDIT_APPLICATION_ID}.
   */
  public Didit() {
    this(new ApiClient(ClientOptions.builder().build()));
  }

  /**
   * A client with this API key and the defaults.
   *
   * @param apiKey the API key from the Business Console
   */
  public Didit(String apiKey) {
    this(new ApiClient(ClientOptions.builder().apiKey(apiKey).build()));
  }

  /**
   * A client with these options.
   *
   * @param options the options
   */
  public Didit(ClientOptions options) {
    this(new ApiClient(options));
  }

  private Didit(ApiClient client) {
    super(client);
    this.client = client;
  }

  /**
   * Returns a builder with the defaults.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder(ClientOptions.builder());
  }

  /**
   * The options of this client.
   *
   * @return the options
   */
  public ClientOptions options() {
    return client.options();
  }

  /**
   * A client that shares this one's connection pool with some options changed.
   *
   * <pre>{@code
   * Didit slow = client.withOptions(o -> o.timeout(Duration.ofMinutes(2)).maxRetries(5));
   * }</pre>
   *
   * @param change changes the options
   * @return a new client
   */
  public Didit withOptions(Consumer<ClientOptions.Builder> change) {
    ClientOptions.Builder builder = client.options().toBuilder();
    change.accept(builder);
    return new Didit(builder.build());
  }

  @Override
  public String toString() {
    return "Didit{" + client.options() + "}";
  }

  /** Builds a {@link Didit} client. Unset values fall back to the environment. */
  public static final class Builder {
    private final ClientOptions.Builder options;

    private Builder(ClientOptions.Builder options) {
      this.options = options;
    }

    /**
     * The API key. Defaults to {@code DIDIT_API_KEY}.
     *
     * @param apiKey the API key from the Business Console
     * @return this builder
     */
    public Builder apiKey(String apiKey) {
      options.apiKey(apiKey);
      return this;
    }

    /**
     * The account token for {@code account} operations.
     *
     * @param accountToken the Bearer token from {@code account().login(...)}
     * @return this builder
     */
    public Builder accountToken(String accountToken) {
      options.accountToken(accountToken);
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
      options.organizationId(organizationId);
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
      options.applicationId(applicationId);
      return this;
    }

    /**
     * The verification API base URL. Defaults to {@code https://verification.didit.me}.
     *
     * @param baseUrl an https URL (http only for localhost)
     * @return this builder
     */
    public Builder baseUrl(String baseUrl) {
      options.baseUrl(baseUrl);
      return this;
    }

    /**
     * The account management API base URL. Defaults to {@code https://apx.didit.me/auth/v2}.
     *
     * @param accountBaseUrl an https URL (http only for localhost)
     * @return this builder
     */
    public Builder accountBaseUrl(String accountBaseUrl) {
      options.accountBaseUrl(accountBaseUrl);
      return this;
    }

    /**
     * The timeout of each attempt. Defaults to 60 seconds.
     *
     * @param timeout a positive duration
     * @return this builder
     */
    public Builder timeout(Duration timeout) {
      options.timeout(timeout);
      return this;
    }

    /**
     * How many times a failed request is retried. Defaults to 2.
     *
     * @param maxRetries zero or more
     * @return this builder
     */
    public Builder maxRetries(int maxRetries) {
      options.maxRetries(maxRetries);
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
      options.defaultHeader(name, value);
      return this;
    }

    /**
     * Sends requests with this {@link HttpClient}; it must not follow redirects.
     *
     * @param httpClient the client
     * @return this builder
     */
    public Builder httpClient(HttpClient httpClient) {
      options.httpClient(httpClient);
      return this;
    }

    /**
     * Sends requests through a custom transport, for example a stub in tests.
     *
     * @param transport the transport
     * @return this builder
     */
    public Builder transport(HttpTransport transport) {
      options.transport(transport);
      return this;
    }

    /**
     * Logs method, path, status, duration and request id of every request. Defaults to {@code
     * DIDIT_LOG=debug}.
     *
     * @param enabled whether to log
     * @return this builder
     */
    public Builder debugLogging(boolean enabled) {
      options.debugLogging(enabled);
      return this;
    }

    /**
     * Where the {@code DIDIT_*} fallbacks are read from. Defaults to {@link System#getenv}.
     *
     * @param environment variable name to value, or {@code null} when unset
     * @return this builder
     */
    public Builder environment(Function<String, @Nullable String> environment) {
      options.environment(environment);
      return this;
    }

    /**
     * Builds the client.
     *
     * @return the client
     * @throws me.didit.errors.ConfigurationException when a URL is invalid
     */
    public Didit build() {
      return new Didit(options.build());
    }
  }
}
