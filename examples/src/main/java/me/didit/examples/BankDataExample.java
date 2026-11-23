package me.didit.examples;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import me.didit.Didit;
import me.didit.errors.DiditException;
import me.didit.errors.WebhookVerificationException;
import me.didit.types.BankAccount;
import me.didit.types.BankAccountList;
import me.didit.types.BankLink;
import me.didit.types.BankLinkCreateRequest;
import me.didit.types.BankLinkEndUser;
import me.didit.webhooks.BankConnectionCreatedEvent;
import me.didit.webhooks.WebhookEvent;
import me.didit.webhooks.Webhooks;

/**
 * Bank data: create a Bank Link, verify {@code bank.connection.created} and read the accounts.
 *
 * <p>Run on your own workstation as the consenting user. Forward signed webhook deliveries to
 * localhost:8080/webhooks/didit.
 */
public final class BankDataExample {
  private BankDataExample() {}

  public static void main(String[] args) throws IOException, InterruptedException {
    String secret = Env.require("DIDIT_WEBHOOK_SECRET");
    if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
      throw new IllegalStateException("Run this example on a desktop with a browser.");
    }
    Didit client = Env.client();
    BankLink link =
        client
            .bank()
            .createLink(
                BankLinkCreateRequest.builder()
                    .mode(BankLinkCreateRequest.Mode.CONNECT)
                    .access(BankLinkCreateRequest.Access.ONE_TIME)
                    .segment(BankLinkCreateRequest.Segment.ANY)
                    .country("ES")
                    .data(List.of(BankLinkCreateRequest.Data.ACCOUNTS))
                    .endUser(BankLinkEndUser.builder().reference("user-42").build())
                    .redirectUrl("https://example.com/bank/done")
                    .build());
    CountDownLatch finished = new CountDownLatch(1);
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8080), 0);
    server.createContext(
        "/webhooks/didit", exchange -> receive(exchange, client, link.id(), secret, finished));
    server.start();
    try {
      System.out.println("Waiting for Bank Link " + link.id());
      try {
        Desktop.getDesktop().browse(URI.create(link.url().orElseThrow()));
      } catch (IOException | IllegalArgumentException ex) {
        throw new IOException("Could not open the Bank Link in your browser.");
      }
      finished.await();
    } finally {
      server.stop(0);
    }
  }

  private static void receive(
      HttpExchange exchange, Didit client, String linkId, String secret, CountDownLatch finished)
      throws IOException {
    try {
      if (!"POST".equals(exchange.getRequestMethod())) {
        exchange.sendResponseHeaders(405, -1);
        return;
      }
      int maxBytes = 1024 * 1024;
      byte[] body = exchange.getRequestBody().readNBytes(maxBytes + 1);
      if (body.length > maxBytes) {
        exchange.sendResponseHeaders(413, -1);
        return;
      }
      boolean matched;
      try {
        WebhookEvent event = Webhooks.verify(body, exchange.getRequestHeaders()::getFirst, secret);
        matched = readMatchingAccounts(client, linkId, event);
      } catch (WebhookVerificationException ex) {
        exchange.sendResponseHeaders(401, -1);
        return;
      } catch (DiditException ex) {
        exchange.sendResponseHeaders(503, -1);
        return;
      }
      exchange.sendResponseHeaders(204, -1);
      if (matched) {
        finished.countDown();
      }
    } finally {
      exchange.close();
    }
  }

  static boolean readMatchingAccounts(Didit client, String linkId, WebhookEvent event) {
    if (!(event instanceof BankConnectionCreatedEvent created)) {
      return false;
    }
    String connectionId = created.data().connectionId();
    if (client.bank().getLink(linkId).connectionId().filter(connectionId::equals).isEmpty()) {
      return false;
    }
    readAccounts(client, connectionId);
    return true;
  }

  static void readAccounts(Didit client, String connectionId) {
    BankAccountList accounts = client.bank().listAccounts(connectionId);
    for (BankAccount account : accounts.items()) {
      System.out.println("Account " + account.id() + " is " + account.status());
    }
  }
}
