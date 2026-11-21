package me.didit.core;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** The default {@link HttpTransport}, built on {@link java.net.http.HttpClient}. */
public final class JdkHttpTransport implements HttpTransport {
  private final HttpClient client;

  /**
   * Wraps an {@link HttpClient}. Configure proxies, TLS and executors on it; it must not follow
   * redirects, so that credentials never reach another origin.
   *
   * @param client the client
   */
  public JdkHttpTransport(HttpClient client) {
    if (client.followRedirects() != HttpClient.Redirect.NEVER) {
      throw new IllegalArgumentException(
          "The HttpClient must not follow redirects (HttpClient.Redirect.NEVER)");
    }
    this.client = client;
  }

  /**
   * A transport with a new {@link HttpClient}.
   *
   * @param connectTimeout the connection timeout
   * @return the transport
   */
  public static JdkHttpTransport create(Duration connectTimeout) {
    return new JdkHttpTransport(
        HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
  }

  @Override
  public CompletableFuture<TransportResponse> send(TransportRequest request) {
    byte[] body = request.body();
    HttpRequest.BodyPublisher publisher =
        body.length == 0
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(body);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(request.uri())
            .timeout(request.timeout())
            .method(request.method(), publisher);
    for (Map.Entry<String, List<String>> header : request.headers().entrySet()) {
      for (String value : header.getValue()) {
        builder.header(header.getKey(), value);
      }
    }
    HttpRequest httpRequest = builder.build();
    if (request.streamResponse()) {
      return client
          .sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
          .thenApply(
              r -> new TransportResponse(r.statusCode(), Headers.of(r.headers().map()), r.body()));
    }
    return client
        .sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
        .thenApply(
            r ->
                new TransportResponse(
                    r.statusCode(),
                    Headers.of(r.headers().map()),
                    new ByteArrayInputStream(r.body())));
  }
}
