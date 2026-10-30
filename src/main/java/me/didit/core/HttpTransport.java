package me.didit.core;

import java.util.concurrent.CompletableFuture;

/**
 * Sends one HTTP request. The default is {@link JdkHttpTransport}; replace it to route requests
 * through your own HTTP stack or to stub the API in tests.
 *
 * <p>Implementations must not follow redirects and must complete the future exceptionally with an
 * {@link java.io.IOException} (or {@link java.net.http.HttpTimeoutException}) when no response is
 * received.
 */
@FunctionalInterface
public interface HttpTransport {
  /**
   * Sends the request.
   *
   * @param request the request
   * @return a future completed with the response; the caller closes its body
   */
  CompletableFuture<TransportResponse> send(TransportRequest request);
}
