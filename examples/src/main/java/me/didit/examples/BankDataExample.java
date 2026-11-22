package me.didit.examples;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import me.didit.Didit;
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
 * <pre>
 * DIDIT_API_KEY=... DIDIT_WEBHOOK_SECRET=... ./gradlew :examples:bankData --args="CONNECTION_ID"
 * </pre>
 *
 * <p>The connection id may also come from {@code DIDIT_BANK_CONNECTION_ID}. Without one, the
 * example stops after printing the hosted link URL.
 */
public final class BankDataExample {
  private BankDataExample() {}

  public static void main(String[] args) {
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
                    .data(
                        List.of(
                            BankLinkCreateRequest.Data.ACCOUNTS,
                            BankLinkCreateRequest.Data.BALANCES))
                    .endUser(BankLinkEndUser.builder().reference("user-42").build())
                    .redirectUrl("https://example.com/bank/done")
                    .build());
    System.out.println("Bank Link " + link.id() + ": send the user to " + link.url().orElse("?"));

    String connectionId =
        args.length > 0 ? args[0] : System.getenv("DIDIT_BANK_CONNECTION_ID");
    if (connectionId != null && !connectionId.isEmpty()) {
      readAccounts(client, connectionId);
    }
  }

  /** In your webhook endpoint, verify the delivery and read the connection it announces. */
  static WebhookEvent onWebhook(
      Didit client, byte[] rawBody, Map<String, String> headers, String secret) {
    WebhookEvent event = Webhooks.verify(rawBody, headers, secret);
    if (event instanceof BankConnectionCreatedEvent) {
      BankConnectionCreatedEvent created = (BankConnectionCreatedEvent) event;
      System.out.println(
          "Webhook: connection "
              + created.data().connectionId()
              + " is "
              + created.data().initialStatus());
      readAccounts(client, created.data().connectionId());
    } else {
      System.out.println(
          "Webhook: "
              + event.webhookType()
              + " "
              + new String(rawBody, StandardCharsets.UTF_8).length()
              + " bytes");
    }
    return event;
  }

  static void readAccounts(Didit client, String connectionId) {
    BankAccountList accounts = client.bank().listAccounts(connectionId);
    for (BankAccount account : accounts.items()) {
      System.out.println("Account " + account.id() + " is " + account.status());
    }
    accounts.nextCursor().ifPresent(cursor -> System.out.println("More accounts: " + cursor));
  }
}
