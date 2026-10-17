package me.didit.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLHandshakeException;
import me.didit.errors.ApiConnectionException;
import me.didit.errors.ApiStatusException;
import me.didit.errors.ApiTimeoutException;
import me.didit.errors.ConfigurationException;
import me.didit.errors.DiditException;
import me.didit.errors.UnexpectedResponseException;
import org.jspecify.annotations.Nullable;

/**
 * Sends API requests: credentials, headers, retries, timeouts, errors and logging (DESIGN 6 and 7).
 * Shared by every resource of a client; thread-safe. Used by the generated resources, not meant for
 * direct use.
 */
public final class ApiClient {
  static final Duration INITIAL_BACKOFF = Duration.ofMillis(500);
  static final Duration MAX_BACKOFF = Duration.ofSeconds(8);
  static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(60);
  private static final System.Logger LOGGER = System.getLogger("me.didit");

  private final ClientOptions options;

  /**
   * Creates the client.
   *
   * @param options the client options
   */
  public ApiClient(ClientOptions options) {
    this.options = options;
  }

  /**
   * The client options.
   *
   * @return the options
   */
  public ClientOptions options() {
    return options;
  }

  /**
   * Sends a request and waits for the result.
   *
   * @param <T> the result type
   * @param request what to send
   * @param requestOptions per-request overrides
   * @param handler parses a successful response
   * @return the parsed response
   * @throws DiditException when the request fails
   */
  public <T extends @Nullable Object> RawResponse<T> execute(
      ApiRequest request, RequestOptions requestOptions, ResponseHandler<T> handler) {
    return await(executeAsync(request, requestOptions, handler));
  }

  /**
   * Sends a request.
   *
   * @param <T> the result type
   * @param request what to send
   * @param requestOptions per-request overrides
   * @param handler parses a successful response
   * @return a future completed with the parsed response, or with a {@link DiditException}
   */
  public <T extends @Nullable Object> CompletableFuture<RawResponse<T>> executeAsync(
      ApiRequest request, RequestOptions requestOptions, ResponseHandler<T> handler) {
    return send(request, requestOptions, handler, null);
  }

  <T extends @Nullable Object> CompletableFuture<RawResponse<T>> send(
      ApiRequest request,
      RequestOptions requestOptions,
      ResponseHandler<T> handler,
      @Nullable URI target) {
    Prepared prepared;
    try {
      prepared = prepare(request, requestOptions, handler.streaming(), target);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
    CompletableFuture<RawResponse<T>> result = new CompletableFuture<>();
    attempt(prepared, request, requestOptions, handler, 0, System.nanoTime(), result);
    return result;
  }

  static <T> T await(CompletableFuture<T> future) {
    try {
      return future.get();
    } catch (InterruptedException e) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new ApiConnectionException("Interrupted while waiting for the response", e);
    } catch (ExecutionException e) {
      throw rethrow(e.getCause());
    }
  }

  static RuntimeException rethrow(@Nullable Throwable cause) {
    Throwable t = cause;
    while ((t instanceof CompletionException || t instanceof ExecutionException)
        && t.getCause() != null) {
      t = t.getCause();
    }
    if (t instanceof RuntimeException) {
      return (RuntimeException) t;
    }
    if (t instanceof Error) {
      throw (Error) t;
    }
    return new DiditException("Request failed", t);
  }

  // -- preparation -------------------------------------------------------------

  static final class Prepared {
    final String method;
    final URI uri;
    final Map<String, List<String>> headers;
    final byte[] body;
    final Duration timeout;
    final int maxRetries;
    final boolean streaming;
    final String logPath;

    Prepared(
        String method,
        URI uri,
        Map<String, List<String>> headers,
        byte[] body,
        Duration timeout,
        int maxRetries,
        boolean streaming,
        String logPath) {
      this.method = method;
      this.uri = uri;
      this.headers = headers;
      this.body = body;
      this.timeout = timeout;
      this.maxRetries = maxRetries;
      this.streaming = streaming;
      this.logPath = logPath;
    }
  }

  URI baseFor(ApiRequest request) {
    return request.api == ApiRequest.Api.ACCOUNT ? options.accountBaseUrl() : options.baseUrl();
  }

  Prepared prepare(ApiRequest request, RequestOptions ro, boolean streaming, @Nullable URI target) {
    URI base = baseFor(request);
    Map<String, List<String>> headers = new LinkedHashMap<>();
    put(
        headers,
        "Accept",
        request.accept == null ? "application/json" : request.accept + ", application/json");
    put(headers, "User-Agent", Platform.userAgent());
    put(headers, "X-Didit-Client", "java/" + Version.VERSION);
    switch (request.auth) {
      case API_KEY:
        String apiKey =
            ro.apiKey()
                .or(options::apiKey)
                .orElseThrow(
                    () ->
                        new ConfigurationException(
                            "No API key: pass apiKey(...) to the client or set DIDIT_API_KEY"));
        put(headers, "x-api-key", apiKey);
        break;
      case ACCOUNT_TOKEN:
        String token =
            ro.accountToken()
                .or(options::accountToken)
                .orElseThrow(
                    () ->
                        new ConfigurationException(
                            "No account token: pass accountToken(...) with the token from"
                                + " account().login(...)"));
        put(headers, "Authorization", "Bearer " + token);
        break;
      case NONE:
        break;
    }

    String path = Paths.expand(request, ro, options);
    ObjectNode fields =
        request.params == null ? Json.MAPPER.createObjectNode() : request.params.json.deepCopy();
    Map<String, List<FileUpload>> files =
        request.params == null ? Map.of() : request.params._files();

    for (Map.Entry<String, String> header : request.headerFields.entrySet()) {
      JsonNode value = fields.remove(header.getValue());
      if (value != null && !value.isNull()) {
        put(headers, header.getKey(), value.isTextual() ? value.asText() : value.toString());
      }
    }
    List<String[]> query = new ArrayList<>();
    for (Map.Entry<String, String> queryField : request.queryFields.entrySet()) {
      JsonNode value = fields.remove(queryField.getValue());
      if (value != null && !value.isNull()) {
        addQuery(query, queryField.getKey(), value);
      }
    }
    for (Map.Entry<String, String> extra : ro.queryParams().entrySet()) {
      query.add(new String[] {extra.getKey(), extra.getValue()});
    }

    URI uri;
    if (target != null) {
      uri = Paths.sameOrigin(base, target);
    } else {
      uri = URI.create(base.toString() + path + Paths.queryString(query));
    }

    byte[] body = new byte[0];
    ApiRequest.BodyEncoding encoding = request.bodyEncoding;
    if (encoding == ApiRequest.BodyEncoding.JSON_OR_MULTIPART) {
      encoding = files.isEmpty() ? ApiRequest.BodyEncoding.JSON : ApiRequest.BodyEncoding.MULTIPART;
    }
    if (encoding == ApiRequest.BodyEncoding.JSON) {
      body = Json.writeBytes(fields);
      put(headers, "Content-Type", "application/json");
    } else if (encoding == ApiRequest.BodyEncoding.MULTIPART) {
      Multipart multipart = Multipart.encode(fields, files);
      body = multipart.body();
      put(headers, "Content-Type", multipart.contentType());
    }

    for (Map.Entry<String, String> header : options.defaultHeaders().entrySet()) {
      replace(headers, header.getKey(), header.getValue());
    }
    ro.idempotencyKey().ifPresent(key -> replace(headers, "Idempotency-Key", key));
    for (Map.Entry<String, String> header : ro.headers().entrySet()) {
      replace(headers, header.getKey(), header.getValue());
    }
    return new Prepared(
        request.method,
        uri,
        headers,
        body,
        ro.timeout().orElse(options.timeout()),
        ro.maxRetries().orElse(options.maxRetries()),
        streaming,
        request.method + " " + uri.getRawPath());
  }

  private static void addQuery(List<String[]> query, String field, JsonNode value) {
    if (value.isArray()) {
      for (JsonNode item : value) {
        if (!item.isNull()) {
          addQuery(query, field, item);
        }
      }
    } else if (value.isTextual()) {
      query.add(new String[] {field, value.asText()});
    } else if (value.isNumber()) {
      query.add(new String[] {field, value.decimalValue().toPlainString()});
    } else {
      query.add(new String[] {field, value.toString()});
    }
  }

  private static void put(Map<String, List<String>> headers, String name, String value) {
    headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
  }

  private static void replace(Map<String, List<String>> headers, String name, String value) {
    headers.keySet().removeIf(k -> k.equalsIgnoreCase(name));
    put(headers, name, value);
  }

  // -- sending -----------------------------------------------------------------

  private <T extends @Nullable Object> void attempt(
      Prepared prepared,
      ApiRequest request,
      RequestOptions ro,
      ResponseHandler<T> handler,
      int retry,
      long started,
      CompletableFuture<RawResponse<T>> result) {
    if (result.isDone()) {
      return; // cancelled by the caller
    }
    TransportRequest transportRequest =
        new TransportRequest(
            prepared.method,
            prepared.uri,
            prepared.headers,
            prepared.body,
            prepared.timeout,
            prepared.streaming);
    CompletableFuture<TransportResponse> sent;
    try {
      sent = options.transport().send(transportRequest);
    } catch (RuntimeException e) {
      sent = CompletableFuture.failedFuture(e);
    }
    CompletableFuture<TransportResponse> inFlight = sent;
    CompletableFuture<?> unused =
        result.whenComplete(
            (r, e) -> {
              if (e instanceof CancellationException) {
                inFlight.cancel(true);
              }
            });
    CompletableFuture<?> unusedAttempt =
        sent.orTimeout(prepared.timeout.toMillis() + 50, TimeUnit.MILLISECONDS)
            .whenComplete(
                (response, error) -> {
                  if (error != null) {
                    inFlight.cancel(true);
                    onFailure(prepared, request, ro, handler, retry, started, result, error);
                  } else {
                    onResponse(prepared, request, ro, handler, retry, started, result, response);
                  }
                });
  }

  private <T extends @Nullable Object> void onFailure(
      Prepared prepared,
      ApiRequest request,
      RequestOptions ro,
      ResponseHandler<T> handler,
      int retry,
      long started,
      CompletableFuture<RawResponse<T>> result,
      Throwable error) {
    Throwable cause = unwrap(error);
    boolean timeout = cause instanceof TimeoutException || cause instanceof HttpTimeoutException;
    boolean beforeSending = RetryPolicy.failedBeforeSending(cause);
    boolean retryable = beforeSending || request.idempotent();
    if (!(cause instanceof IOException) && !(cause instanceof TimeoutException)) {
      retryable = false; // a bug or a misconfigured transport, not a network failure
    }
    if (retryable && retry < prepared.maxRetries) {
      scheduleRetry(prepared, request, ro, handler, retry, started, result, Optional.empty());
      return;
    }
    DiditException failure;
    if (timeout && !(cause instanceof HttpConnectTimeoutException)) {
      failure =
          new ApiTimeoutException(
              "Request timed out after " + prepared.timeout.toMillis() + " ms: " + prepared.logPath,
              cause);
    } else if (cause instanceof IOException || cause instanceof TimeoutException) {
      failure =
          new ApiConnectionException(
              "Connection error on " + prepared.logPath + ": " + describe(cause), cause);
    } else if (cause instanceof DiditException) {
      failure = (DiditException) cause;
    } else {
      failure = new ApiConnectionException("Request failed: " + prepared.logPath, cause);
    }
    log(prepared, retry, started, failure.getClass().getSimpleName(), Optional.empty());
    result.completeExceptionally(failure);
  }

  private <T extends @Nullable Object> void onResponse(
      Prepared prepared,
      ApiRequest request,
      RequestOptions ro,
      ResponseHandler<T> handler,
      int retry,
      long started,
      CompletableFuture<RawResponse<T>> result,
      TransportResponse response) {
    int status = response.statusCode();
    Headers headers = response.headers();
    if (status >= 200 && status < 300) {
      log(prepared, retry, started, Integer.toString(status), headers.requestId());
      try {
        SuccessResponse success =
            new SuccessResponse(this, request, ro, prepared.uri, response, handler.streaming());
        T data = handler.handle(success);
        result.complete(new RawResponse<>(status, headers, data));
      } catch (RuntimeException e) {
        response.close();
        result.completeExceptionally(e);
      } catch (IOException e) {
        response.close();
        result.completeExceptionally(
            new ApiConnectionException("Failed to read the response of " + prepared.logPath, e));
      }
      return;
    }
    if (RetryPolicy.retryableStatus(status, request.idempotent()) && retry < prepared.maxRetries) {
      response.close();
      scheduleRetry(
          prepared,
          request,
          ro,
          handler,
          retry,
          started,
          result,
          headers.firstValue("Retry-After"));
      return;
    }
    byte[] bytes;
    try {
      bytes = response.readBody();
    } catch (IOException e) {
      bytes = new byte[0];
    }
    JsonNode body = null;
    if (bytes.length > 0) {
      try {
        body = Json.parse(bytes);
      } catch (UnexpectedResponseException e) {
        body = Json.nodes().textNode(new String(bytes, StandardCharsets.UTF_8));
      }
    }
    log(prepared, retry, started, Integer.toString(status), headers.requestId());
    result.completeExceptionally(
        ApiStatusException.create(status, body, headers, prepared.logPath));
  }

  private <T extends @Nullable Object> void scheduleRetry(
      Prepared prepared,
      ApiRequest request,
      RequestOptions ro,
      ResponseHandler<T> handler,
      int retry,
      long started,
      CompletableFuture<RawResponse<T>> result,
      Optional<String> retryAfter) {
    Duration delay =
        RetryPolicy.delay(retry, retryAfter, ThreadLocalRandom.current().nextDouble(), now());
    if (options.debugLogging()) {
      LOGGER.log(
          System.Logger.Level.INFO,
          "didit: retrying {0} in {1} ms (retry {2} of {3})",
          prepared.logPath,
          delay.toMillis(),
          retry + 1,
          prepared.maxRetries);
    }
    CompletableFuture<?> unused =
        CompletableFuture.runAsync(
            () -> attempt(prepared, request, ro, handler, retry + 1, started, result),
            options.scheduler().after(delay));
  }

  static ZonedDateTime now() {
    return ZonedDateTime.now(java.time.ZoneOffset.UTC);
  }

  private void log(
      Prepared prepared, int retry, long started, String outcome, Optional<String> requestId) {
    if (!options.debugLogging()) {
      return;
    }
    long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    LOGGER.log(
        System.Logger.Level.INFO,
        "didit: {0} -> {1} in {2} ms, retries {3}, request id {4}",
        prepared.logPath,
        outcome,
        millis,
        retry,
        requestId.orElse("none"));
  }

  private static Throwable unwrap(Throwable error) {
    Throwable t = error;
    while ((t instanceof CompletionException || t instanceof ExecutionException)
        && t.getCause() != null) {
      t = t.getCause();
    }
    return t;
  }

  private static String describe(Throwable cause) {
    String message = cause.getMessage();
    return cause.getClass().getSimpleName() + (message == null ? "" : " (" + message + ")");
  }

  /** Retry decisions (DESIGN 7). */
  static final class RetryPolicy {
    private RetryPolicy() {}

    /** True when the request certainly did not reach the server. */
    static boolean failedBeforeSending(Throwable cause) {
      for (Throwable t = cause; t != null; t = t.getCause()) {
        if (t instanceof HttpConnectTimeoutException
            || t instanceof ConnectException
            || t instanceof UnknownHostException
            || t instanceof NoRouteToHostException
            || t instanceof UnresolvedAddressException
            || t instanceof SSLHandshakeException) {
          return true;
        }
      }
      return false;
    }

    /** 408, 429 and 5xx for idempotent methods; only 429 for POST and PATCH. */
    static boolean retryableStatus(int status, boolean idempotent) {
      if (status == 429) {
        return true;
      }
      return idempotent && (status == 408 || status >= 500);
    }

    /**
     * The wait before retry number {@code retry + 1}: {@code Retry-After} when valid (capped at 60
     * s), otherwise exponential backoff from 0.5 s with full jitter, capped at 8 s.
     */
    static Duration delay(
        int retry, Optional<String> retryAfter, double jitter, ZonedDateTime now) {
      Optional<Duration> fromHeader = retryAfter.flatMap(value -> parseRetryAfter(value, now));
      if (fromHeader.isPresent()) {
        Duration d = fromHeader.get();
        return d.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : d;
      }
      double ceiling =
          Math.min(
              (double) MAX_BACKOFF.toMillis(),
              (double) INITIAL_BACKOFF.toMillis() * Math.pow(2, Math.min(retry, 20)));
      return Duration.ofMillis((long) (ceiling * jitter));
    }

    static Optional<Duration> parseRetryAfter(String value, ZonedDateTime now) {
      String v = value.trim();
      try {
        double seconds = Double.parseDouble(v);
        if (seconds >= 0 && !Double.isInfinite(seconds) && !Double.isNaN(seconds)) {
          return Optional.of(Duration.ofMillis((long) (seconds * 1000)));
        }
        return Optional.empty();
      } catch (NumberFormatException e) {
        // Not seconds; maybe an HTTP date.
      }
      try {
        ZonedDateTime at = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME);
        Duration d = Duration.between(now, at);
        return Optional.of(d.isNegative() ? Duration.ZERO : d);
      } catch (DateTimeParseException e) {
        return Optional.empty();
      }
    }
  }

  /** Paths, query strings and origins. */
  static final class Paths {
    private Paths() {}

    static String expand(ApiRequest request, RequestOptions ro, ClientOptions options) {
      Map<String, String> values = new LinkedHashMap<>(request.pathParams);
      if (request.applicationScoped) {
        Optional<String> org = ro.organizationId().or(options::organizationId);
        Optional<String> app = ro.applicationId().or(options::applicationId);
        if (org.isEmpty() || app.isEmpty()) {
          throw new ConfigurationException(
              request.describe()
                  + " is application scoped: set organizationId and applicationId on the client"
                  + " (or DIDIT_ORGANIZATION_ID and DIDIT_APPLICATION_ID)");
        }
        values.put("organization_id", org.get());
        values.put("application_id", app.get());
      }
      StringBuilder out = new StringBuilder();
      String template = request.pathTemplate;
      int i = 0;
      while (i < template.length()) {
        int open = template.indexOf('{', i);
        if (open < 0) {
          out.append(template, i, template.length());
          break;
        }
        int close = template.indexOf('}', open);
        out.append(template, i, open);
        String name = template.substring(open + 1, close);
        String value = values.get(name);
        if (value == null) {
          throw new ConfigurationException("Missing path parameter '" + name + "'");
        }
        out.append(segment(name, value));
        i = close + 1;
      }
      return out.toString();
    }

    static String segment(String name, String value) {
      if (value.isEmpty() || value.equals(".") || value.equals("..")) {
        throw new ConfigurationException(
            "Path parameter '" + name + "' must not be empty, '.' or '..'");
      }
      return encode(value);
    }

    static String encode(String value) {
      StringBuilder out = new StringBuilder();
      for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
        int c = b & 0xff;
        if ((c >= 'a' && c <= 'z')
            || (c >= 'A' && c <= 'Z')
            || (c >= '0' && c <= '9')
            || c == '-'
            || c == '.'
            || c == '_'
            || c == '~') {
          out.append((char) c);
        } else {
          out.append('%').append(String.format(Locale.ROOT, "%02X", c));
        }
      }
      return out.toString();
    }

    static String queryString(List<String[]> query) {
      if (query.isEmpty()) {
        return "";
      }
      StringBuilder out = new StringBuilder("?");
      for (String[] pair : query) {
        if (out.length() > 1) {
          out.append('&');
        }
        out.append(encode(pair[0])).append('=').append(encode(pair[1]));
      }
      return out.toString();
    }

    /**
     * Resolves a next-page link against the base URL. The link must point to the same host and
     * port; the base scheme is kept, so a plain-http link from a proxy never downgrades the call.
     */
    static URI sameOrigin(URI base, URI link) {
      URI resolved = base.resolve(link);
      String host = resolved.getHost();
      if (host == null
          || !host.equalsIgnoreCase(base.getHost())
          || (port(resolved) != port(base) && !(isDefault(resolved) && isDefault(base)))) {
        throw new UnexpectedResponseException(
            "Refusing to follow a next page link to another origin: "
                + resolved.getScheme()
                + "://"
                + resolved.getAuthority());
      }
      String query = resolved.getRawQuery();
      return URI.create(
          base.getScheme()
              + "://"
              + base.getRawAuthority()
              + resolved.getRawPath()
              + (query == null ? "" : "?" + query));
    }

    private static int port(URI uri) {
      if (uri.getPort() != -1) {
        return uri.getPort();
      }
      return "http".equalsIgnoreCase(uri.getScheme()) ? 80 : 443;
    }

    private static boolean isDefault(URI uri) {
      return uri.getPort() == -1;
    }
  }
}
