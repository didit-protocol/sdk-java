package me.didit.examples;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A tiny local stand-in for the Didit API with canned, synthetic responses. It is only for the
 * runnable demo and tests; it checks that the SDK sends an API key.
 */
public final class MockDiditServer implements AutoCloseable {
  private final HttpServer server;
  private final List<String> log = Collections.synchronizedList(new ArrayList<>());

  public MockDiditServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/v3/session/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          if (exchange.getRequestHeaders().getFirst("x-api-key") == null) {
            send(exchange, 401, "{\"detail\":\"Missing API key\"}");
          } else if (exchange.getRequestMethod().equals("POST") && path.equals("/v3/session/")) {
            send(
                exchange,
                201,
                "{\"session_id\":\"11111111-2222-4333-8444-555555555555\",\"session_number\":1,"
                    + "\"session_token\":\"demo\","
                    + "\"url\":\"https://verify.didit.me/session/synthetic\","
                    + "\"vendor_data\":\"user-42\",\"metadata\":null,\"status\":\"Not Started\","
                    + "\"callback\":\"https://example.com/verification/done\","
                    + "\"workflow_id\":\"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee\",\"workflow_version\":3}");
          } else if (path.endsWith("/decision/")) {
            send(
                exchange,
                200,
                "{\"session_id\":\"11111111-2222-4333-8444-555555555555\",\"status\":\"Approved\","
                    + "\"workflow_id\":\"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee\",\"vendor_data\":\"user-42\"}");
          } else {
            send(exchange, 404, "{\"detail\":\"Not found\"}");
          }
        });
    server.start();
  }

  private void send(HttpExchange exchange, int status, String body) throws IOException {
    log.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " -> " + status);
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.getResponseHeaders().add("X-Request-Id", "req-mock-" + log.size());
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  public List<String> log() {
    return List.copyOf(log);
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
