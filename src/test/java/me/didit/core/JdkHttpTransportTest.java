package me.didit.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import me.didit.Didit;
import me.didit.errors.ApiConnectionException;
import me.didit.errors.ApiStatusException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The default transport against a loopback HTTP server (no external network). */
final class JdkHttpTransportTest {
  private HttpServer server;
  private final AtomicReference<String> lastKey = new AtomicReference<>();
  private final AtomicReference<String> lastBody = new AtomicReference<>();

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/v3/echo/",
        exchange -> {
          lastKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
          lastBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.getResponseHeaders().add("X-Request-Id", "req-local");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.createContext(
        "/v3/redirect/",
        exchange -> {
          exchange.getResponseHeaders().add("Location", "https://evil.example.com/");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.createContext(
        "/v3/pdf/",
        exchange -> {
          byte[] body = "%PDF-1.7 report".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/pdf");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private ApiClient client() {
    return new ApiClient(
        ClientOptions.builder()
            .apiKey("local-key")
            .environment(n -> null)
            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
            .timeout(Duration.ofSeconds(5))
            .build());
  }

  @Test
  void sendsAndReceives() {
    RawResponse<JsonNode> r =
        client()
            .execute(
                ApiRequest.builder("POST", "/v3/echo/")
                    .params(Pet.builder().name("Rex").build())
                    .body(ApiRequest.BodyEncoding.JSON)
                    .build(),
                RequestOptions.none(),
                Responses.jsonNode());
    assertEquals(200, r.statusCode());
    assertEquals("req-local", r.requestId().orElseThrow());
    assertEquals("local-key", lastKey.get());
    assertEquals("{\"name\":\"Rex\"}", lastBody.get());
  }

  @Test
  void streamsBinaryBodies() {
    BinaryResponse pdf =
        client()
            .execute(
                ApiRequest.builder("GET", "/v3/pdf/").accept("application/pdf").build(),
                RequestOptions.none(),
                Responses.binary())
            .data();
    assertEquals("%PDF-1.7 report", new String(pdf.bytes(), StandardCharsets.UTF_8));
  }

  @Test
  void neverFollowsRedirects() {
    ApiStatusException e =
        assertThrows(
            ApiStatusException.class,
            () ->
                client()
                    .execute(
                        ApiRequest.builder("GET", "/v3/redirect/").build(),
                        RequestOptions.none(),
                        Responses.jsonNode()));
    assertEquals(302, e.statusCode());
  }

  @Test
  void connectionRefused() throws Exception {
    int port = server.getAddress().getPort();
    server.stop(0);
    ApiClient client =
        new ApiClient(
            ClientOptions.builder()
                .apiKey("k")
                .environment(n -> null)
                .baseUrl("http://127.0.0.1:" + port)
                .maxRetries(0)
                .build());
    assertThrows(
        ApiConnectionException.class,
        () ->
            client.execute(
                ApiRequest.builder("GET", "/v3/echo/").build(),
                RequestOptions.none(),
                Responses.jsonNode()));
    start();
  }

  @Test
  void acceptsACustomHttpClient() {
    HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    Didit didit =
        Didit.builder()
            .apiKey("k")
            .environment(n -> null)
            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
            .httpClient(http)
            .build();
    assertTrue(didit.options().transport() instanceof JdkHttpTransport);
  }
}
