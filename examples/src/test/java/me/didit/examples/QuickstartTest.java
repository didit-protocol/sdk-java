package me.didit.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import me.didit.Didit;
import me.didit.webhooks.VerifiedBy;
import me.didit.webhooks.WebhookEvent;
import me.didit.webhooks.Webhooks;
import org.junit.jupiter.api.Test;

/** The README quickstart against the local mock API. */
final class QuickstartTest {
  @Test
  void quickstartRunsAgainstTheMock() throws Exception {
    try (MockDiditServer server = new MockDiditServer()) {
      Didit client =
          Didit.builder()
              .apiKey("test-key")
              .baseUrl(server.baseUrl())
              .environment(n -> null)
              .build();
      assertEquals(
          "11111111-2222-4333-8444-555555555555",
          Quickstart.run(client, "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"));
      assertEquals(
          List.of(
              "POST /v3/session/ -> 201",
              "GET /v3/session/11111111-2222-4333-8444-555555555555/decision/ -> 200"),
          server.log());
    }
    String secret = "whsec_test_quickstart_0123";
    long now = Instant.now().getEpochSecond();
    String body =
        "{\"status\":\"Approved\",\"timestamp\":" + now + ",\"webhook_type\":\"status.updated\"}";
    Map<String, String> headers =
        Map.of("X-Signature-V2", QuickstartDemo.hmac(secret, Webhooks.canonicalJson(body)));
    WebhookEvent event =
        Quickstart.onWebhook(body.getBytes(StandardCharsets.UTF_8), headers, secret);
    assertEquals(VerifiedBy.SIGNATURE_V2, event.verifiedBy());
  }
}
