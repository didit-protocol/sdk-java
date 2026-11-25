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
    BankLinkEvent failed = assertInstanceOf(BankLinkFailedEvent.class, verify("renewal_failed"));
    assertEquals(target, failed.renewsConnectionId());
    assertEquals(Optional.empty(), failed.data().connectionId());
    assertEquals(target, renewalTarget(failed));

    BankLinkEvent completed =
        assertInstanceOf(BankLinkCompletedEvent.class, verify("renewal_completed"));
    assertEquals(target, completed.renewsConnectionId());
    assertEquals(target, completed.data().connectionId());
    assertEquals(target, renewalTarget(completed));

    BankLinkEvent legacy =
        assertInstanceOf(BankLinkFailedEvent.class, verify("legacy_renewal_failed"));
    assertEquals(Optional.empty(), legacy.renewsConnectionId());
    assertEquals(target, legacy.data().connectionId());
    assertEquals(target, renewalTarget(legacy));

    BankLinkEvent newLink =
        assertInstanceOf(BankLinkCompletedEvent.class, verify("new_link_completed"));
    assertEquals(Optional.empty(), newLink.renewsConnectionId());

    BankLinkEvent stored =
        assertInstanceOf(BankLinkCompletedEvent.class, verify(BankLinkCompletedEvent.TYPE));
    assertEquals(Optional.empty(), stored.renewsConnectionId());
    assertEquals(target, stored.data().connectionId());
  }

  /** The correlation the README documents: the renewal target when present, else the connection. */
  private static Optional<String> renewalTarget(BankLinkEvent event) {
    Optional<String> renews = event.renewsConnectionId();
    return renews.isPresent() ? renews : event.data().connectionId();
  }

  @Test
  void readsBothConnectionUpdateShapes() {
    BankConnectionUpdatedEvent pending =
        assertInstanceOf(
            BankConnectionUpdatedEvent.class, verify(BankConnectionUpdatedEvent.TYPE));
    assertEquals(
        BankConnectionUpdatedData.RevocationStatus.PENDING,
        pending.revocationStatus().orElseThrow());
    assertEquals(Optional.empty(), pending.statusReason());
    assertEquals(Optional.empty(), pending.endsAt());

    BankConnectionUpdatedEvent due =
        assertInstanceOf(BankConnectionUpdatedEvent.class, verify("payment_due"));
    assertEquals("paused", due.data().status().value());
    assertEquals(Optional.empty(), due.revocationStatus());
    assertEquals(Optional.of("insufficient_balance"), due.statusReason());
    assertEquals(OffsetDateTime.parse("2026-12-01T00:00:00Z"), due.endsAt().orElseThrow());

    BankConnectionUpdatedEvent paid =
        assertInstanceOf(BankConnectionUpdatedEvent.class, verify("payment_received"));
    assertEquals("active", paid.data().status().value());
    assertEquals(Optional.empty(), paid.revocationStatus());
    assertEquals(Optional.empty(), paid.statusReason());
    assertEquals(Optional.empty(), paid.endsAt());
  }

  @Test
  void readsUnpaidSystemRevocation() {
    BankConnectionRevokedData revoked =
        assertInstanceOf(BankConnectionRevokedEvent.class, verify("unpaid_revocation")).data();
    assertEquals("unpaid", revoked.statusReason().orElseThrow().value());
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
