package me.didit.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.didit.Didit;
import me.didit.errors.ConfigurationException;
import me.didit.errors.WebhookVerificationException;
import me.didit.testing.TestJson;
import org.junit.jupiter.api.Test;

final class WebhooksTest {
  // Synthetic test secrets only (allowed by .gitleaks.toml).
  static final String SECRET = "whsec_test_unit_0123456789abcdef";
  static final String OTHER = "whsec_test_unit_other_fedcba9876543210";
  static final long NOW = 1_800_000_000L;

  static VerifyOptions.Builder options(String... secrets) {
    return VerifyOptions.builder()
        .secrets(List.of(secrets))
        .clock(Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));
  }

  static String body(String type, long timestamp, String extra) {
    return "{\"webhook_type\":\"" + type + "\",\"timestamp\":" + timestamp + ",\"event_id\":\"e-1\","
        + "\"application_id\":\"app-1\",\"environment\":\"sandbox\",\"created_at\":" + timestamp
        + extra + "}";
  }

  /** Signs like the backend: X-Signature over the bytes, V2 over the canonical JSON. */
  static Map<String, String> sign(String body, String secret) {
    Map<String, String> h = new LinkedHashMap<>();
    h.put("X-Signature", Webhooks.hmac(secret, body.getBytes(StandardCharsets.UTF_8)));
    h.put(
        "X-Signature-V2",
        Webhooks.hmac(secret, Webhooks.canonicalJson(body).getBytes(StandardCharsets.UTF_8)));
    ObjectNode payload = Webhooks.parse(body.getBytes(StandardCharsets.UTF_8));
    h.put(
        "X-Signature-Simple",
        Webhooks.hmac(secret, Webhooks.simpleString(payload).getBytes(StandardCharsets.UTF_8)));
    h.put("X-Timestamp", payload.get("timestamp").asText());
    return h;
  }

  static WebhookVerificationException.Reason reject(String body, Map<String, ?> headers, VerifyOptions o) {
    return assertThrows(WebhookVerificationException.class, () -> Webhooks.verify(body, headers, o))
        .reason();
  }

  @Test
  void verifiesAndTypesASessionEvent() {
    String body =
        body(
            "status.updated",
            NOW,
            ",\"session_id\":\"s-1\",\"status\":\"Approved\",\"session_kind\":\"user\","
                + "\"workflow_id\":\"w-1\",\"workflow_version\":3,\"vendor_data\":\"user-42\","
                + "\"metadata\":{\"plan\":\"pro\"},\"business_session_id\":null,"
                + "\"decision\":{\"session_id\":\"s-1\",\"status\":\"Approved\"},"
                + "\"resubmit_info\":{\"reasons\":{}}");
    WebhookEvent event = Webhooks.verify(body, sign(body, SECRET), options(SECRET).build());
    SessionStatusUpdatedEvent e = assertInstanceOf(SessionStatusUpdatedEvent.class, event);
    assertEquals("status.updated", e.webhookType());
    assertEquals("s-1", e.sessionId().orElseThrow());
    assertEquals("Approved", e.status().orElseThrow());
    assertEquals("user", e.sessionKind().orElseThrow());
    assertEquals("w-1", e.workflowId().orElseThrow());
    assertEquals(3L, e.workflowVersion().orElseThrow());
    assertEquals("user-42", e.vendorData().orElseThrow());
    assertEquals("pro", e.metadata().orElseThrow().get("plan").asText());
    assertTrue(e.businessSessionId().isEmpty());
    assertEquals("s-1", e.decision().orElseThrow().sessionId().orElseThrow());
    assertTrue(e.resubmitInfo().isPresent());
    assertEquals("e-1", e.eventId().orElseThrow());
    assertEquals(NOW, e.timestamp().orElseThrow());
    assertEquals(NOW, e.createdAt().orElseThrow());
    assertEquals("app-1", e.applicationId().orElseThrow());
    assertEquals("sandbox", e.environment().orElseThrow());
    assertTrue(e.isSandbox());
    assertTrue(e.isBodyAuthenticated());
    assertTrue(e.toString().contains("status.updated"));
  }

  @Test
  void typesEveryKnownEvent() {
    Map<String, Class<?>> types = new LinkedHashMap<>();
    types.put("data.updated", SessionDataUpdatedEvent.class);
    types.put("user.status.updated", UserStatusUpdatedEvent.class);
    types.put("user.data.updated", UserDataUpdatedEvent.class);
    types.put("business.status.updated", BusinessStatusUpdatedEvent.class);
    types.put("business.data.updated", BusinessDataUpdatedEvent.class);
    types.put("activity.created", ActivityCreatedEvent.class);
    types.put("transaction.created", TransactionCreatedEvent.class);
    types.put("transaction.status.updated", TransactionStatusUpdatedEvent.class);
    types.put("travel_rule.status.updated", TravelRuleStatusUpdatedEvent.class);
    types.put("workflow.compliance_changed", WorkflowComplianceChangedEvent.class);
    types.put("auth.user.signed_in", UnknownEvent.class);
    for (Map.Entry<String, Class<?>> t : types.entrySet()) {
      String body = body(t.getKey(), NOW, "");
      WebhookEvent event = Webhooks.verify(body, sign(body, SECRET), options(SECRET).build());
      assertInstanceOf(t.getValue(), event, t.getKey());
    }
  }

  @Test
  void transactionTravelRuleUserAndBusinessFields() {
    String tx =
        body(
            "transaction.status.updated",
            NOW,
            ",\"transaction_id\":\"t-1\",\"txn_id\":\"ref-1\",\"status\":\"APPROVED\",\"score\":12.5,"
                + "\"severity\":\"LOW\",\"amount\":\"100.00\",\"currency\":\"EUR\",\"direction\":\"inbound\","
                + "\"action_required\":{\"type\":\"verification_session\",\"url\":\"https://example.com/v\"}");
    TransactionStatusUpdatedEvent t =
        (TransactionStatusUpdatedEvent) Webhooks.verify(tx, sign(tx, SECRET), options(SECRET).build());
    assertEquals("t-1", t.transactionId().orElseThrow());
    assertEquals("ref-1", t.txnId().orElseThrow());
    assertEquals("APPROVED", t.status().orElseThrow());
    assertEquals(12.5, t.score().orElseThrow());
    assertEquals("LOW", t.severity().orElseThrow());
    assertEquals("100.00", t.amount().orElseThrow());
    assertEquals("EUR", t.currency().orElseThrow());
    assertEquals("inbound", t.direction().orElseThrow());
    assertTrue(t.actionRequired().orElseThrow().isVerificationSession());

    String tr =
        body(
            "travel_rule.status.updated",
            NOW,
            ",\"transaction_id\":\"t-1\",\"txn_id\":\"ref-1\",\"travel_rule_status\":\"COMPLETED\","
                + "\"rail\":\"TRP\",\"direction\":\"outbound\"");
    TravelRuleStatusUpdatedEvent r =
        (TravelRuleStatusUpdatedEvent) Webhooks.verify(tr, sign(tr, SECRET), options(SECRET).build());
    assertEquals("t-1", r.transactionId().orElseThrow());
    assertEquals("ref-1", r.txnId().orElseThrow());
    assertEquals("COMPLETED", r.travelRuleStatus().orElseThrow());
    assertEquals("TRP", r.rail().orElseThrow());
    assertEquals("outbound", r.direction().orElseThrow());

    String user =
        body("user.status.updated", NOW, ",\"vendor_user_id\":\"u-1\",\"vendor_data\":\"v\",\"status\":\"ACTIVE\"");
    UserStatusUpdatedEvent u =
        (UserStatusUpdatedEvent) Webhooks.verify(user, sign(user, SECRET), options(SECRET).build());
    assertEquals("u-1", u.vendorUserId().orElseThrow());
    assertEquals("v", u.vendorData().orElseThrow());
    assertEquals("ACTIVE", u.status().orElseThrow());

    String biz =
        body(
            "business.data.updated",
            NOW,
            ",\"vendor_business_id\":\"b-1\",\"vendor_data\":\"v\",\"status\":\"ACTIVE\"");
    BusinessDataUpdatedEvent b =
        (BusinessDataUpdatedEvent) Webhooks.verify(biz, sign(biz, SECRET), options(SECRET).build());
    assertEquals("b-1", b.vendorBusinessId().orElseThrow());
    assertEquals("v", b.vendorData().orElseThrow());
    assertEquals("ACTIVE", b.status().orElseThrow());

    String wf = body("workflow.compliance_changed", NOW, ",\"workflow_id\":\"w-9\"");
    WorkflowComplianceChangedEvent w =
        (WorkflowComplianceChangedEvent) Webhooks.verify(wf, sign(wf, SECRET), options(SECRET).build());
    assertEquals("w-9", w.workflowId().orElseThrow());
  }

  @Test
  void unknownEventsKeepThePayload() {
    String body = body("transaction_rule_suggestions.created", NOW, ",\"extra\":[1,2]");
    UnknownEvent e =
        (UnknownEvent) Webhooks.verify(body, sign(body, SECRET), options(SECRET).build());
    assertEquals(2, e.payload().get("extra").size());
    assertEquals("transaction_rule_suggestions.created", e.webhookType());
  }

  @Test
  void unsignedRegistrySearchIsRejectedButParsable() {
    String body = "{\"event_type\":\"kyb.registry_search.resolved\",\"timestamp\":" + NOW + "}";
    Map<String, String> headers = Map.of("X-Didit-Unsigned-Callback", "true");
    assertEquals(
        WebhookVerificationException.Reason.MISSING_SIGNATURE,
        reject(body, headers, options(SECRET).build()));
    WebhookEvent event = Webhooks.parseEvent(TestJson.parse(body));
    assertInstanceOf(KybRegistrySearchResolvedEvent.class, event);
    assertEquals(VerifiedBy.NONE, event.verifiedBy());
    assertFalse(event.isBodyAuthenticated());
    assertEquals("", Webhooks.parseEvent(TestJson.parse("{}")).webhookType());
  }

  @Test
  void rotationAcceptsAnyConfiguredSecretAndPreviousHeaders() {
    String body = body("status.updated", NOW, "");
    Map<String, String> signedWithOld = sign(body, OTHER);
    assertEquals(
        WebhookVerificationException.Reason.INVALID_SIGNATURE,
        reject(body, signedWithOld, options(SECRET).build()));
    assertEquals(
        VerifiedBy.SIGNATURE,
        Webhooks.verify(body, signedWithOld, options(SECRET, OTHER).build()).verifiedBy());
    Map<String, String> previousOnly = new HashMap<>();
    previousOnly.put("X-Signature", "0".repeat(64));
    previousOnly.put("X-Signature-Previous", sign(body, SECRET).get("X-Signature"));
    assertEquals(
        VerifiedBy.SIGNATURE, Webhooks.verify(body, previousOnly, options(SECRET).build()).verifiedBy());
    Map<String, String> v2Previous = new HashMap<>();
    v2Previous.put("X-Signature-V2-Previous", sign(body, SECRET).get("X-Signature-V2"));
    assertEquals(
        VerifiedBy.SIGNATURE_V2, Webhooks.verify(body, v2Previous, options(SECRET).build()).verifiedBy());
    Map<String, String> simplePrevious = new HashMap<>();
    simplePrevious.put("X-Signature-Simple-Previous", sign(body, SECRET).get("X-Signature-Simple"));
    assertEquals(
        VerifiedBy.SIGNATURE_SIMPLE,
        Webhooks.verify(body, simplePrevious, options(SECRET).allowSimple(true).build()).verifiedBy());
  }

  @Test
  void headersAreCaseInsensitiveTrimmedAndMayBeLists() {
    String body = body("status.updated", NOW, "");
    Map<String, String> signed = sign(body, SECRET);
    Map<String, Object> headers = new LinkedHashMap<>();
    headers.put("x-signature", List.of(" " + signed.get("X-Signature") + " "));
    headers.put("X-TIMESTAMP", new String[] {String.valueOf(NOW)});
    assertEquals(VerifiedBy.SIGNATURE, Webhooks.verify(body, headers, options(SECRET).build()).verifiedBy());
    // A function lookup, like request::getHeader.
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    assertEquals(
        VerifiedBy.SIGNATURE,
        Webhooks.verify(bytes, name -> signed.get(name), options(SECRET).build()).verifiedBy());
    // An empty value counts as absent.
    assertEquals(
        WebhookVerificationException.Reason.MISSING_SIGNATURE,
        reject(body, Map.of("X-Signature", " "), options(SECRET).build()));
    assertEquals(
        WebhookVerificationException.Reason.MISSING_SIGNATURE,
        reject(body, Map.of("X-Signature", List.of()), options(SECRET).build()));
  }

  @Test
  void onlyWellFormedLowercaseHexIsCompared() {
    String body = body("status.updated", NOW, "");
    String upper = sign(body, SECRET).get("X-Signature").toUpperCase(java.util.Locale.ROOT);
    assertEquals(
        WebhookVerificationException.Reason.INVALID_SIGNATURE,
        reject(body, Map.of("X-Signature", upper), options(SECRET).build()));
  }

  @Test
  void timestampRules() {
    String body = body("status.updated", NOW, "");
    Map<String, String> h = new HashMap<>(sign(body, SECRET));
    h.put("X-Timestamp", "abc");
    assertEquals(WebhookVerificationException.Reason.TIMESTAMP_MISMATCH, reject(body, h, options(SECRET).build()));
    h.put("X-Timestamp", String.valueOf(NOW + 1));
    assertEquals(WebhookVerificationException.Reason.TIMESTAMP_MISMATCH, reject(body, h, options(SECRET).build()));
    h.remove("X-Timestamp");
    assertEquals(VerifiedBy.SIGNATURE, Webhooks.verify(body, h, options(SECRET).build()).verifiedBy());

    // The tolerance bound is inclusive.
    String edge = body("status.updated", NOW - 300, "");
    assertEquals(VerifiedBy.SIGNATURE, Webhooks.verify(edge, sign(edge, SECRET), options(SECRET).build()).verifiedBy());
    String stale = body("status.updated", NOW - 301, "");
    assertEquals(
        WebhookVerificationException.Reason.TIMESTAMP_OUT_OF_TOLERANCE,
        reject(stale, sign(stale, SECRET), options(SECRET).build()));
    assertEquals(
        VerifiedBy.SIGNATURE,
        Webhooks.verify(stale, sign(stale, SECRET), options(SECRET).tolerance(Duration.ofMinutes(10)).build())
            .verifiedBy());

    for (String ts : new String[] {"\"1800000000\"", "1800000000.0", "true", "null"}) {
      String b = "{\"webhook_type\":\"status.updated\",\"timestamp\":" + ts + "}";
      assertEquals(
          WebhookVerificationException.Reason.MISSING_TIMESTAMP,
          reject(b, Map.of("X-Signature", "0".repeat(64)), options(SECRET).build()),
          ts);
    }
    String none = "{\"webhook_type\":\"status.updated\"}";
    assertEquals(
        WebhookVerificationException.Reason.MISSING_TIMESTAMP,
        reject(none, Map.of(), options(SECRET).build()));
  }

  @Test
  void invalidPayloads() {
    VerifyOptions o = options(SECRET).build();
    for (String body : new String[] {"", "[]", "not json", "{\"a\":1}{", "{\"timestamp\":NaN}"}) {
      assertEquals(WebhookVerificationException.Reason.INVALID_PAYLOAD, reject(body, Map.of(), o), body);
    }
    byte[] notUtf8 = {'{', '"', 'a', '"', ':', '"', (byte) 0xff, '"', '}'};
    assertEquals(
        WebhookVerificationException.Reason.INVALID_PAYLOAD,
        assertThrows(WebhookVerificationException.class, () -> Webhooks.verify(notUtf8, Map.of(), o))
            .reason());
    assertEquals(
        WebhookVerificationException.Reason.INVALID_PAYLOAD,
        assertThrows(
                WebhookVerificationException.class,
                () -> Webhooks.verifyParsed(TestJson.parse("[1]"), Map.of(), o))
            .reason());
    assertThrows(WebhookVerificationException.class, () -> Webhooks.parseEvent(TestJson.parse("1")));
  }

  @Test
  void simpleSignatureOnlyWhenAllowedAndNeverAuthenticatesTheBody() {
    String body = body("status.updated", NOW, ",\"session_id\":null,\"status\":true");
    Map<String, String> simple = Map.of("X-Signature-Simple", sign(body, SECRET).get("X-Signature-Simple"));
    assertEquals(WebhookVerificationException.Reason.INVALID_SIGNATURE, reject(body, simple, options(SECRET).build()));
    WebhookEvent event = Webhooks.verify(body, simple, options(SECRET).allowSimple(true).build());
    assertEquals(VerifiedBy.SIGNATURE_SIMPLE, event.verifiedBy());
    assertFalse(event.isBodyAuthenticated());
    // Python's str(): None and True.
    assertEquals(NOW + ":None:True:status.updated", Webhooks.simpleString(Webhooks.parse(body.getBytes(StandardCharsets.UTF_8))));
  }

  @Test
  void pythonStrOfOtherValues() {
    assertEquals("", Webhooks.pythonStr(null));
    assertEquals("False", Webhooks.pythonStr(TestJson.parse("false")));
    assertEquals("1.5", Webhooks.pythonStr(TestJson.parse("1.5")));
    assertEquals("1e+16", Webhooks.pythonStr(TestJson.parse("1e16")));
    assertEquals("[1, 'a', None]", Webhooks.pythonStr(TestJson.parse("[1,\"a\",null]")));
    assertEquals("{'k': \"it's\"}", Webhooks.pythonStr(TestJson.parse("{\"k\":\"it's\"}")));
  }

  @Test
  void configurationErrors() {
    String body = body("status.updated", NOW, "");
    assertThrows(ConfigurationException.class, () -> Webhooks.verify(body, Map.of(), " "));
    assertThrows(
        ConfigurationException.class,
        () -> Webhooks.verify(body, Map.of(), VerifyOptions.builder().build()));
    assertThrows(IllegalArgumentException.class, () -> VerifyOptions.builder().tolerance(Duration.ofSeconds(-1)));
    assertTrue(VerifyOptions.of(SECRET).toString().contains("secrets=1"));
    assertFalse(VerifyOptions.of(SECRET).toString().contains(SECRET));
  }

  @Test
  void parsedBodiesCheckTheCanonicalSignatureOnly() {
    String body = body("status.updated", Instant.now().getEpochSecond(), ",\"score\":0.1");
    Map<String, String> signed = sign(body, SECRET);
    Map<String, Object> payload = new LinkedHashMap<>();
    TestJson.parse(body).properties().forEach(e -> payload.put(e.getKey(), e.getValue()));
    assertEquals(VerifiedBy.SIGNATURE_V2, Webhooks.verifyParsed(payload, signed, SECRET).verifiedBy());
    Map<String, String> rawOnly = Map.of("X-Signature", signed.get("X-Signature"));
    assertEquals(
        WebhookVerificationException.Reason.INVALID_SIGNATURE,
        assertThrows(
                WebhookVerificationException.class,
                () -> Webhooks.verifyParsed(TestJson.parse(body), rawOnly, SECRET))
            .reason());
  }

  @Test
  void clientHelpersDelegate() {
    Didit client = Didit.builder().environment(name -> null).build();
    String body = body("status.updated", Instant.now().getEpochSecond(), "");
    Map<String, String> signed = sign(body, SECRET);
    assertInstanceOf(SessionStatusUpdatedEvent.class, client.webhooks().verify(body, signed, SECRET));
    assertInstanceOf(
        SessionStatusUpdatedEvent.class,
        client.webhooks().verify(body.getBytes(StandardCharsets.UTF_8), signed, VerifyOptions.of(SECRET)));
    assertInstanceOf(
        SessionStatusUpdatedEvent.class,
        client.webhooks().verify(body.getBytes(StandardCharsets.UTF_8), signed::get, VerifyOptions.of(SECRET)));
    assertInstanceOf(
        SessionStatusUpdatedEvent.class,
        client.webhooks().verifyParsed(TestJson.parse(body), signed, SECRET));
    assertEquals(
        VerifiedBy.SIGNATURE,
        Webhooks.verify(body.getBytes(StandardCharsets.UTF_8), signed, SECRET).verifiedBy());
    assertEquals(VerifiedBy.SIGNATURE, Webhooks.verify(body, signed, VerifyOptions.of(SECRET)).verifiedBy());
    assertEquals(
        VerifiedBy.SIGNATURE,
        Webhooks.verify(body.getBytes(StandardCharsets.UTF_8), signed::get, SECRET).verifiedBy());
  }
}
