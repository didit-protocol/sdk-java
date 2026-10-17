package me.didit.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import me.didit.errors.WebhookVerificationException;
import me.didit.testing.SpecExamples;
import me.didit.testing.TestJson;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Runs every vector of openapi/webhook-vectors.json (DESIGN 9.3). */
final class WebhookVectorsTest {
  static Stream<Arguments> vectors() {
    JsonNode doc = SpecExamples.document("webhook-vectors.json");
    List<Arguments> out = new ArrayList<>();
    for (JsonNode vector : doc.get("vectors")) {
      out.add(Arguments.of(vector.get("name").asText(), vector));
    }
    return out.stream();
  }

  private static VerifyOptions options(JsonNode vector, boolean allowSimple) {
    return VerifyOptions.builder()
        .secret(vector.get("secret").asText())
        .tolerance(java.time.Duration.ofSeconds(SpecExamples.document("webhook-vectors.json").get("tolerance_seconds").asLong()))
        .allowSimple(allowSimple)
        .clock(Clock.fixed(Instant.ofEpochSecond(vector.get("now").asLong()), ZoneOffset.UTC))
        .build();
  }

  private static Map<String, String> headers(JsonNode vector) {
    Map<String, String> headers = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> e : vector.get("headers").properties()) {
      headers.put(e.getKey(), e.getValue().asText());
    }
    return headers;
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("vectors")
  void canonicalJsonMatchesTheBackend(String name, JsonNode vector) {
    String canonical = Webhooks.canonicalJson(vector.get("body").asText());
    if (name.equals("tampered_body")) {
      // The vector keeps the canonical string from before the tampering.
      assertNotEquals(vector.get("canonical_v2").asText(), canonical);
    } else {
      assertEquals(vector.get("canonical_v2").asText(), canonical);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("vectors")
  void verifiesLikeTheBackend(String name, JsonNode vector) {
    String body = vector.get("body").asText();
    Map<String, String> headers = headers(vector);
    String expect = vector.get("expect").asText();
    switch (expect) {
      case "valid":
        {
          WebhookEvent event = Webhooks.verify(body, headers, options(vector, false));
          assertTrue(event.isBodyAuthenticated());
          assertEquals(VerifiedBy.SIGNATURE, event.verifiedBy());
          TestJson.assertJsonEquals(TestJson.parse(body), event._json());
          // The canonical signature alone is enough, also for an already parsed body.
          Map<String, String> v2Only = new LinkedHashMap<>(headers);
          v2Only.remove("X-Signature");
          v2Only.remove("X-Signature-Previous");
          WebhookEvent fromV2 =
              Webhooks.verify(body.getBytes(StandardCharsets.UTF_8), v2Only, options(vector, false));
          assertEquals(VerifiedBy.SIGNATURE_V2, fromV2.verifiedBy());
          WebhookEvent parsed =
              Webhooks.verifyParsed(TestJson.parse(body), headers, options(vector, false));
          assertEquals(VerifiedBy.SIGNATURE_V2, parsed.verifiedBy());
          break;
        }
      case "valid_only_with_allow_simple":
        {
          WebhookVerificationException e =
              assertThrows(
                  WebhookVerificationException.class,
                  () -> Webhooks.verify(body, headers, options(vector, false)));
          assertEquals("invalid_signature", e.reason().code());
          WebhookEvent event = Webhooks.verify(body, headers, options(vector, true));
          assertEquals(VerifiedBy.SIGNATURE_SIMPLE, event.verifiedBy());
          assertFalse(event.isBodyAuthenticated());
          break;
        }
      default:
        {
          WebhookVerificationException e =
              assertThrows(
                  WebhookVerificationException.class,
                  () -> Webhooks.verify(body, headers, options(vector, false)));
          assertEquals(expect, e.reason().code());
        }
    }
  }
}
