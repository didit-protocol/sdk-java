package me.didit.examples;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import me.didit.Didit;
import me.didit.types.SessionsCreateParams;
import me.didit.types.SessionsCreateResponse;
import me.didit.types.SessionsRetrieveDecisionResponse;
import me.didit.webhooks.SessionStatusUpdatedEvent;
import me.didit.webhooks.WebhookEvent;
import me.didit.webhooks.Webhooks;

/**
 * The README quickstart: create a verification session, read its decision and verify a webhook.
 *
 * <pre>
 * DIDIT_API_KEY=... DIDIT_WORKFLOW_ID=... DIDIT_WEBHOOK_SECRET=... ./gradlew :examples:quickstart
 * </pre>
 */
public final class Quickstart {
  private Quickstart() {}

  public static void main(String[] args) {
    Didit client = Env.client();
    String workflowId = Env.require("DIDIT_WORKFLOW_ID");
    run(client, workflowId);
  }

  static String run(Didit client, String workflowId) {
    // 1. Create a session and send the user to its URL.
    SessionsCreateResponse session =
        client
            .sessions()
            .create(
                SessionsCreateParams.builder()
                    .workflowId(workflowId)
                    .vendorData("user-42")
                    .callback("https://example.com/verification/done")
                    .build());
    System.out.println("Session " + session.sessionId() + ": send the user to " + session.url());

    // 2. Later, read the decision.
    SessionsRetrieveDecisionResponse decision =
        client.sessions().retrieveDecision(session.sessionId());
    System.out.println(
        "Decision: " + decision.status().map(Object::toString).orElse("not available yet"));
    return session.sessionId();
  }

  /** 3. In your webhook endpoint, verify the delivery before trusting it. */
  static WebhookEvent onWebhook(byte[] rawBody, Map<String, String> headers, String secret) {
    WebhookEvent event = Webhooks.verify(rawBody, headers, secret);
    if (event instanceof SessionStatusUpdatedEvent) {
      SessionStatusUpdatedEvent e = (SessionStatusUpdatedEvent) event;
      System.out.println(
          "Webhook: session "
              + e.sessionId().orElse("?")
              + " is now "
              + e.status().orElse("?")
              + " (verified by "
              + e.verifiedBy()
              + ")");
    } else {
      System.out.println("Webhook: " + event.webhookType() + " " + new String(rawBody, StandardCharsets.UTF_8).length() + " bytes");
    }
    return event;
  }
}
