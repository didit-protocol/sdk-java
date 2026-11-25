package me.didit.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import me.didit.testing.TestJson;
import me.didit.types.BankConnectionRevokedData;
import me.didit.types.BankConnectionUpdatedData;
import me.didit.types.BankLinkEventData;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Types every {@code bank.*} event and reads its generated payload type. */
final class BankWebhookEventsTest {
  /** Signed deliveries of the bank.* events: a hand-written fixture, not an upstream copy. */
  private static final JsonNode BANK_VECTORS = loadBankVectors();

  private static JsonNode loadBankVectors() {
    try (InputStream in =
        BankWebhookEventsTest.class.getResourceAsStream("/bank-webhook-vectors.json")) {
      return TestJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @TestFactory
  Stream<DynamicTest> verifiesAndTypesEveryBankEvent() {
    return Stream.of(
        delivery(
            BankLinkCompletedEvent.TYPE,
            BankLinkCompletedEvent.class,
            e -> assertEquals("completed", e.data().status().value())),
        delivery(
            BankLinkCancelledEvent.TYPE,
            BankLinkCancelledEvent.class,
            e -> assertEquals("cancelled", e.data().status().value())),
        delivery(
            BankLinkExpiredEvent.TYPE,
            BankLinkExpiredEvent.class,
            e -> assertEquals("expired", e.data().status().value())),
        delivery(
            BankLinkFailedEvent.TYPE,
            BankLinkFailedEvent.class,
            e -> assertEquals("duplicate_conflict", e.data().errorCode().orElseThrow().value())),
        delivery(
            BankConnectionCreatedEvent.TYPE,
            BankConnectionCreatedEvent.class,
            e -> assertEquals("succeeded", e.data().initialStatus().value())),
        delivery(
            BankConnectionUpdatedEvent.TYPE,
            BankConnectionUpdatedEvent.class,
            e -> assertEquals("active", e.data().status().value())),
        delivery(
            BankConnectionConsentExpiringEvent.TYPE,
            BankConnectionConsentExpiringEvent.class,
            e -> assertEquals(7, e.data().daysLeft())),
        delivery(
            BankConnectionRenewalRequiredEvent.TYPE,
            BankConnectionRenewalRequiredEvent.class,
            e -> assertEquals("consent_expired", e.data().reason().value())),
        delivery(
            BankConnectionRenewedEvent.TYPE,
            BankConnectionRenewedEvent.class,
            e -> assertEquals(2, e.data().consentVersion())),
        delivery(
            BankConnectionRevokedEvent.TYPE,
            BankConnectionRevokedEvent.class,
            e -> assertEquals("bank", e.data().endedBy().value())),
        delivery(
            BankConnectionDeletedEvent.TYPE,
            BankConnectionDeletedEvent.class,
            e -> assertEquals("erased", e.data().erasureStatus().value())),
        delivery(
            BankConnectionHistoryCompleteEvent.TYPE,
            BankConnectionHistoryCompleteEvent.class,
            e -> assertEquals("complete", e.data().status().value())),
        delivery(
            BankDataUpdatedEvent.TYPE,
            BankDataUpdatedEvent.class,
            e -> assertEquals("scheduled", e.data().trigger().value())),
        delivery(
            BankSyncCompletedEvent.TYPE,
            BankSyncCompletedEvent.class,
            e -> assertEquals("partial", e.data().outcome().value())),
        delivery(
            BankSyncFailedEvent.TYPE,
            BankSyncFailedEvent.class,
            e -> assertEquals("bank_unavailable", e.data().errorCode())),
        delivery(
            BankSyncRecoveredEvent.TYPE,
            BankSyncRecoveredEvent.class,
            e -> assertEquals("eeeeeeee-0000-4000-8000-000000000002", e.data().syncId())));
  }

  private static <E extends BankEvent> DynamicTest delivery(
      String type, Class<E> expected, Consumer<E> checkPayload) {
    return dynamicTest(
        type,
        () -> {
          E event = assertInstanceOf(expected, verify(type));
          assertEquals(type, event.webhookType());
          checkPayload.accept(event);
        });
  }

  @Test
  void readsGeneratedBankPayloads() {
    BankLinkCompletedEvent link = (BankLinkCompletedEvent) verify(BankLinkCompletedEvent.TYPE);
    assertEquals("bbbbbbbb-0000-4000-8000-000000000001", link.data().linkId());
    assertEquals("completed", link.data().status().value());
    assertEquals("cccccccc-0000-4000-8000-000000000001", link.data().connectionId().orElseThrow());

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

  @Test
  void correlatesRenewalLinksAcrossRedeliveries() {
    Optional<String> target = Optional.of("cccccccc-0000-4000-8000-000000000001");
    BankLinkEventData failed =
        assertInstanceOf(BankLinkFailedEvent.class, verify("renewal_failed")).data();
    assertEquals(target, failed.renewsConnectionId());
    assertEquals(Optional.empty(), failed.connectionId());

    BankLinkEventData completed =
        assertInstanceOf(BankLinkCompletedEvent.class, verify("renewal_completed")).data();
    assertEquals(target, completed.renewsConnectionId());
    assertEquals(target, completed.connectionId());

    BankLinkEventData legacy =
        assertInstanceOf(BankLinkFailedEvent.class, verify("legacy_renewal_failed")).data();
    assertEquals(Optional.empty(), legacy.renewsConnectionId());
    assertEquals(target, legacy.connectionId());

    BankLinkEventData newLink =
        assertInstanceOf(BankLinkCompletedEvent.class, verify("new_link_completed")).data();
    assertEquals(Optional.empty(), newLink.renewsConnectionId());
  }

  @Test
  void readsBothConnectionUpdateShapes() {
    BankConnectionUpdatedData pending =
        assertInstanceOf(BankConnectionUpdatedEvent.class, verify(BankConnectionUpdatedEvent.TYPE))
            .data();
    assertEquals("pending", pending.revocationStatus().orElseThrow().value());
    assertEquals(Optional.empty(), pending.statusReason());
    assertEquals(Optional.empty(), pending.endsAt());

    BankConnectionUpdatedData due =
        assertInstanceOf(BankConnectionUpdatedEvent.class, verify("payment_due")).data();
    assertEquals(Optional.empty(), due.revocationStatus());
    assertEquals("insufficient_balance", due.statusReason().orElseThrow().value());
    assertEquals(OffsetDateTime.parse("2026-12-01T00:00:00Z"), due.endsAt().orElseThrow());

    BankConnectionUpdatedData paid =
        assertInstanceOf(BankConnectionUpdatedEvent.class, verify("payment_received")).data();
    assertEquals(Optional.empty(), paid.revocationStatus());
    assertEquals(Optional.empty(), paid.statusReason());
    assertEquals(Optional.empty(), paid.endsAt());
  }

  @Test
  void readsUnpaidSystemRevocation() {
    BankConnectionRevokedData revoked =
        assertInstanceOf(BankConnectionRevokedEvent.class, verify("unpaid_revocation")).data();
    assertEquals(
        BankConnectionRevokedData.StatusReason.UNPAID, revoked.statusReason().orElseThrow());
    assertEquals(BankConnectionRevokedData.EndedBy.SYSTEM, revoked.endedBy());
  }

  private static WebhookEvent verify(String name) {
    JsonNode vector = vector(name.replace('.', '_'));
    Map<String, String> headers = new LinkedHashMap<>();
    vector.get("headers").properties().forEach(e -> headers.put(e.getKey(), e.getValue().asText()));
    VerifyOptions options =
        VerifyOptions.builder()
            .secret(vector.get("secret").asText())
            .tolerance(Duration.ofSeconds(BANK_VECTORS.get("tolerance_seconds").asLong()))
            .clock(Clock.fixed(Instant.ofEpochSecond(vector.get("now").asLong()), ZoneOffset.UTC))
            .build();
    return Webhooks.verify(vector.get("body").asText(), headers, options);
  }

  private static JsonNode vector(String name) {
    for (JsonNode vector : BANK_VECTORS.get("vectors")) {
      if (name.equals(vector.get("name").asText())) {
        return vector;
      }
    }
    throw new AssertionError("No webhook vector for " + name);
  }
}
