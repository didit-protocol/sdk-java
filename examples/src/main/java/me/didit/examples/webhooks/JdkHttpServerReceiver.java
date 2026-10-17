package me.didit.examples.webhooks;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import me.didit.errors.WebhookVerificationException;
import me.didit.webhooks.SessionStatusUpdatedEvent;
import me.didit.webhooks.VerifyOptions;
import me.didit.webhooks.WebhookEvent;
import me.didit.webhooks.Webhooks;

/** A webhook receiver with only the JDK: {@code com.sun.net.httpserver}. */
public final class JdkHttpServerReceiver {
  private JdkHttpServerReceiver() {}

  public static void main(String[] args) throws IOException {
    VerifyOptions options = VerifyOptions.of(System.getenv("DIDIT_WEBHOOK_SECRET"));
    HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
    server.createContext(
        "/webhooks/didit",
        exchange -> {
          byte[] body = exchange.getRequestBody().readAllBytes();
          int status;
          try {
            WebhookEvent event =
                Webhooks.verify(body, exchange.getRequestHeaders()::getFirst, options);
            if (event instanceof SessionStatusUpdatedEvent) {
              System.out.println("Session " + ((SessionStatusUpdatedEvent) event).sessionId().orElse("?"));
            }
            status = 200;
          } catch (WebhookVerificationException e) {
            status = 401;
          }
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    server.start();
  }
}
