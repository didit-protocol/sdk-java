package me.didit.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import me.didit.testing.SpecExamples;
import me.didit.testing.TestJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Types every {@code bank.*} event and reads its generated payload type. */
final class BankWebhookEventsTest {
  static Stream<Arguments> bankEvents() {
    Map<String, Class<? extends BankEvent>> types = new LinkedHashMap<>();
    types.put(BankLinkCompletedEvent.TYPE, BankLinkCompletedEvent.class);
    types.put(BankLinkCancelledEvent.TYPE, BankLinkCancelledEvent.class);
    types.put(BankLinkExpiredEvent.TYPE, BankLinkExpiredEvent.class);
    types.put(BankLinkFailedEvent.TYPE, BankLinkFailedEvent.class);
    types.put(BankConnectionCreatedEvent.TYPE, BankConnectionCreatedEvent.class);
    types.put(BankConnectionUpdatedEvent.TYPE, BankConnectionUpdatedEvent.class);
    types.put(BankConnectionConsentExpiringEvent.TYPE, BankConnectionConsentExpiringEvent.class);
    types.put(BankConnectionRenewalRequiredEvent.TYPE, BankConnectionRenewalRequiredEvent.class);
    types.put(BankConnectionRenewedEvent.TYPE, BankConnectionRenewedEvent.class);
    types.put(BankConnectionRevokedEvent.TYPE, BankConnectionRevokedEvent.class);
    types.put(BankConnectionDeletedEvent.TYPE, BankConnectionDeletedEvent.class);
    types.put(BankConnectionHistoryCompleteEvent.TYPE, BankConnectionHistoryCompleteEvent.class);
    types.put(BankDataUpdatedEvent.TYPE, BankDataUpdatedEvent.class);
    types.put(BankSyncCompletedEvent.TYPE, BankSyncCompletedEvent.class);
    types.put(BankSyncFailedEvent.TYPE, BankSyncFailedEvent.class);
    types.put(BankSyncRecoveredEvent.TYPE, BankSyncRecoveredEvent.class);
    return types.entrySet().stream().map(e -> Arguments.of(e.getKey(), e.getValue()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("bankEvents")
  void verifiesAndTypesEveryBankEvent(String type, Class<? extends BankEvent> expected) {
    BankEvent event = assertInstanceOf(BankEvent.class, verify(type));
    assertInstanceOf(expected, event);
    assertEquals(type, event.webhookType());
    assertNotNull(event.data());
  }

  @Test
  void readsGeneratedBankPayloads() {
    BankLinkCompletedEvent link = (BankLinkCompletedEvent) verify(BankLinkCompletedEvent.TYPE);
    assertEquals("bbbbbbbb-0000-4000-8000-000000000001", link.data().linkId());
    assertEquals("completed", link.data().status().value());
    assertEquals(
        "cccccccc-0000-4000-8000-000000000001",
        link.data().connectionId().orElseThrow());

    BankConnectionCreatedEvent created =
        (BankConnectionCreatedEvent) verify(BankConnectionCreatedEvent.TYPE);
    assertEquals("succeeded", created.data().initialStatus().value());

    BankDataUpdatedEvent updated = (BankDataUpdatedEvent) verify(BankDataUpdatedEvent.TYPE);
    assertEquals("scheduled", updated.data().trigger().value());
    assertEquals(1, updated.data().accounts().size());
    assertEquals(3, updated.data().accounts().get(0).added());

    BankSyncFailedEvent failed = (BankSyncFailedEvent) verify(BankSyncFailedEvent.TYPE);
    assertEquals("bank_unavailable", failed.data().errorCode());
  }

  private static WebhookEvent verify(String type) {
    JsonNode vector = vector(type);
    Map<String, String> headers = new LinkedHashMap<>();
    vector.get("headers").properties().forEach(e -> headers.put(e.getKey(), e.getValue().asText()));
    VerifyOptions options =
        VerifyOptions.builder()
            .secret(vector.get("secret").asText())
            .tolerance(
                Duration.ofSeconds(
                    SpecExamples.document("webhook-vectors.json")
                        .get("tolerance_seconds")
                        .asLong()))
            .clock(Clock.fixed(Instant.ofEpochSecond(vector.get("now").asLong()), ZoneOffset.UTC))
            .build();
    return Webhooks.verify(vector.get("body").asText(), headers, options);
  }

  private static JsonNode vector(String type) {
    for (JsonNode vector : SpecExamples.document("webhook-vectors.json").get("vectors")) {
      if (vector.get("name").asText().endsWith("_rotation")) {
        continue;
      }
      JsonNode payload = TestJson.parse(vector.get("body").asText());
      if (type.equals(payload.get("webhook_type").asText())) {
        return vector;
      }
    }
    throw new AssertionError("No webhook vector for " + type);
  }
}
