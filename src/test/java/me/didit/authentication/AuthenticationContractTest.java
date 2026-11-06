package me.didit.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import me.didit.core.Headers;
import me.didit.core.TransportRequest;
import me.didit.core.TransportResponse;
import me.didit.testing.SpecExamples;
import me.didit.testing.TestJson;
import org.junit.jupiter.api.Test;

/**
 * DESIGN 11 for the hand-written Authentication module (DESIGN 10). {@code
 * openapi/authentication.json} is synced from docs.didit.me like the other documents, but nothing
 * is generated from it. Every {@code server: true} operation in it (its {@code x-didit-sdk} method,
 * or each of its variants) must be a public method of {@link Authentication}, and each one is sent
 * to a stub issuer to check its HTTP method, path, content type and grant against the document and
 * that it reads the document's own example responses. A spec sync that adds, removes or renames an
 * Authentication operation fails here until the module follows.
 */
final class AuthenticationContractTest {
  private static final Set<String> HTTP = Set.of("get", "post", "put", "patch", "delete");

  /** Public methods that are not API operations. */
  private static final Set<String> HELPERS = Set.of("issuer", "toString");

  /**
   * Token verification fetches the discovery document and the keys itself (DESIGN 10): these
   * operations have no method of their own, the verify methods stand for them.
   */
  private static final Set<String> FETCHED_BY_VERIFICATION =
      Set.of("getOpenidConfiguration", "getJwks");

  private static final Set<String> VERIFIERS = Set.of("verifyAccessToken", "verifyIdToken");

  private static final String ACCESS_TOKEN = "at-contract";
  private static final String STEP_UP_ID = "94efc2c3-ca84-42fe-900b-6fb61669a7a1";
  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

  private static final JsonNode DOCUMENT = SpecExamples.document("authentication.json");

  /** The issuer the document's examples are written for: its server URL with the default host. */
  private static final String ISSUER =
      "https://" + DOCUMENT.at("/servers/0/variables/issuer/default").asText();

  /** One documented operation, or one variant of it (a token grant). */
  static final class Operation {
    final String http;
    final String path;
    final Pattern pattern;
    final Set<String> contentTypes;
    final String grantProperty;
    final String grant;

    /** The example bodies of each 2xx response; an empty list when the response has no body. */
    final Map<Integer, List<JsonNode>> examples;

    Operation(
        String http,
        String path,
        Set<String> contentTypes,
        String grantProperty,
        String grant,
        Map<Integer, List<JsonNode>> examples) {
      this.http = http;
      this.path = path;
      this.pattern = Pattern.compile("^" + path.replaceAll("\\{[^}]+}", "[^/]+") + "$");
      this.contentTypes = contentTypes;
      this.grantProperty = grantProperty;
      this.grant = grant;
      this.examples = examples;
    }
  }

  /** The Java method of every server operation (each variant counts), by name. */
  static Map<String, Operation> operations() {
    Map<String, Operation> out = new TreeMap<>();
    for (Map.Entry<String, JsonNode> path : DOCUMENT.get("paths").properties()) {
      for (Map.Entry<String, JsonNode> entry : path.getValue().properties()) {
        if (!HTTP.contains(entry.getKey())) {
          continue;
        }
        JsonNode op = entry.getValue();
        JsonNode sdk = op.get("x-didit-sdk");
        assertEquals("authentication", sdk.path("resource").asText(), path.getKey());
        if (!sdk.path("server").asBoolean()) {
          continue;
        }
        JsonNode content = deref(op.path("requestBody")).path("content");
        Set<String> contentTypes = new TreeSet<>();
        content.fieldNames().forEachRemaining(contentTypes::add);
        Iterator<JsonNode> mediaTypes = content.elements();
        String grantProperty =
            mediaTypes.hasNext()
                ? deref(mediaTypes.next().path("schema"))
                    .path("discriminator")
                    .path("propertyName")
                    .asText("")
                : "";
        Map<Integer, List<JsonNode>> examples = new TreeMap<>();
        for (Map.Entry<String, JsonNode> response : op.get("responses").properties()) {
          if (!response.getKey().startsWith("2")) {
            continue;
          }
          List<JsonNode> bodies = new ArrayList<>();
          for (JsonNode media : deref(response.getValue()).path("content")) {
            for (JsonNode example : media.path("examples")) {
              bodies.add(deref(example).get("value"));
            }
          }
          examples.put(Integer.parseInt(response.getKey()), bodies);
        }
        List<JsonNode> variants = new ArrayList<>();
        sdk.path("variants").forEach(variants::add);
        if (variants.isEmpty()) {
          variants.add(sdk);
        }
        for (JsonNode variant : variants) {
          String name = camel(variant.get("method").asText());
          Operation previous =
              out.put(
                  name,
                  new Operation(
                      entry.getKey().toUpperCase(Locale.ROOT),
                      path.getKey(),
                      contentTypes,
                      grantProperty,
                      variant.path("discriminator").asText(""),
                      examples));
          assertNull(previous, "two operations name the method " + name);
        }
      }
    }
    return out;
  }

  /** The methods of the operations server SDKs never expose (device or end-user tokens). */
  static Set<String> hidden() {
    Set<String> out = new TreeSet<>();
    for (JsonNode item : DOCUMENT.get("paths")) {
      for (Map.Entry<String, JsonNode> entry : item.properties()) {
        JsonNode sdk = entry.getValue().path("x-didit-sdk");
        if (HTTP.contains(entry.getKey()) && !sdk.path("server").asBoolean()) {
          out.add(camel(sdk.get("method").asText()));
        }
      }
    }
    return out;
  }

  static String camel(String snake) {
    StringBuilder out = new StringBuilder();
    boolean upper = false;
    for (int i = 0; i < snake.length(); i++) {
      char c = snake.charAt(i);
      if (c == '_') {
        upper = true;
      } else {
        out.append(upper ? Character.toUpperCase(c) : c);
        upper = false;
      }
    }
    return out.toString();
  }

  static JsonNode deref(JsonNode node) {
    JsonNode current = node;
    while (current.has("$ref")) {
      current = DOCUMENT.at(current.get("$ref").asText().substring(1));
    }
    return current;
  }

  /** The public instance methods of the module, without their Async twins. */
  static Set<String> moduleMethods(boolean withAsync) {
    Set<String> out = new TreeSet<>();
    for (Method m : Authentication.class.getDeclaredMethods()) {
      if (!Modifier.isPublic(m.getModifiers()) || Modifier.isStatic(m.getModifiers())) {
        continue;
      }
      String name = m.getName();
      if (withAsync || !name.endsWith("Async")) {
        out.add(name);
      }
    }
    return out;
  }

  @Test
  void documentOperationsAreTheModuleMethods() {
    Set<String> documented = operations().keySet();
    Set<String> expected = new TreeSet<>(documented);
    expected.removeAll(FETCHED_BY_VERIFICATION);
    if (documented.containsAll(FETCHED_BY_VERIFICATION)) {
      expected.addAll(VERIFIERS);
    }
    Set<String> methods = moduleMethods(false);
    methods.removeAll(HELPERS);
    Set<String> missing = new TreeSet<>(expected);
    missing.removeAll(methods);
    Set<String> extra = new TreeSet<>(methods);
    extra.removeAll(expected);
    assertEquals(Set.of(), missing, "documented operations without a method");
    assertEquals(Set.of(), extra, "methods without a documented operation");
    // Every operation also has its Async twin.
    Set<String> all = moduleMethods(true);
    for (String name : expected) {
      assertTrue(all.contains(name + "Async"), name + "Async");
    }
    // Device and end-user token operations are never in a server SDK (DESIGN 4).
    assertFalse(hidden().isEmpty());
    for (String name : hidden()) {
      assertFalse(all.contains(name), name + " is not a server operation");
    }
  }

  @Test
  void everyOperationSendsTheDocumentedRequestAndReadsTheDocumentedResponse() {
    Map<String, Operation> operations = operations();
    assertTrue(operations.keySet().containsAll(FETCHED_BY_VERIFICATION));
    for (Map.Entry<String, Operation> entry : operations.entrySet()) {
      String method = entry.getKey();
      Operation operation = entry.getValue();
      assertFalse(operation.examples.isEmpty(), method + " has no success response");
      for (Map.Entry<Integer, List<JsonNode>> response : operation.examples.entrySet()) {
        List<JsonNode> bodies = new ArrayList<>(response.getValue());
        if (bodies.isEmpty()) {
          bodies.add(NullNode.getInstance());
        }
        for (JsonNode body : bodies) {
          check(method, operation, response.getKey(), body);
        }
      }
    }
  }

  private static void check(String method, Operation operation, int status, JsonNode body) {
    StubIssuer issuer = new StubIssuer();
    ObjectNode discovery =
        (ObjectNode) operations().get("getOpenidConfiguration").examples.get(200).get(0).deepCopy();
    issuer.routes.put(
        "GET /.well-known/openid-configuration",
        r -> StubIssuer.json(200, discovery, issuer.cacheControl));
    boolean fetched = FETCHED_BY_VERIFICATION.contains(method);
    if (!fetched) {
      String route = operation.http + " " + operation.path.replace("{step_up_id}", STEP_UP_ID);
      issuer.routes.put(route, r -> reply(status, body));
    }
    Authentication auth =
        Authentication.builder()
            .issuer(ISSUER)
            .transport(issuer)
            .clock(Clock.fixed(NOW, ZoneOffset.UTC))
            .build();
    Object result = call(auth, method, issuer);

    TransportRequest sent = sent(issuer, method, operation);
    assertTrue(sent.uri().toString().startsWith(ISSUER + "/"), method);
    if (!operation.contentTypes.isEmpty()) {
      String type = Headers.of(sent.headers()).firstValue("Content-Type").orElse("");
      assertTrue(
          operation.contentTypes.contains(type.split(";", 2)[0].trim()),
          method + " sends " + type + ", the document accepts " + operation.contentTypes);
    }
    if (!operation.grantProperty.isEmpty()) {
      assertEquals(operation.grant, form(sent).get(operation.grantProperty), method);
    }
    if (fetched) {
      // The key set is the one the discovery example names; the verified claims come back.
      assertEquals(
          discovery.get("jwks_uri").asText(),
          issuer.last("GET /.well-known/jwks.json").uri().toString());
      assertTrue(result instanceof AccessTokenClaims, method);
    } else if (body.isObject()) {
      assertEquals(body, ((JsonView) result)._json(), method + " reads the documented " + status);
    }
  }

  /** How the module performs each documented operation. */
  private static Object call(Authentication auth, String method, StubIssuer issuer) {
    switch (method) {
      case "getOpenidConfiguration":
      case "getJwks":
        {
          ObjectNode claims = issuer.accessClaims(NOW);
          claims.put("iss", ISSUER);
          return auth.verifyAccessToken(issuer.sign(issuer.header("at+jwt"), claims));
        }
      case "exchangeCode":
        return auth.exchangeCode("code-1", "verifier-1", "https://app.example.com/callback");
      case "refresh":
        return auth.refresh("rt-1");
      case "getSession":
        return auth.getSession(ACCESS_TOKEN);
      case "revokeSession":
        auth.revokeSession(ACCESS_TOKEN);
        return "revoked";
      case "createStepUp":
        return auth.createStepUp(
            ACCESS_TOKEN,
            StepUpParams.builder("payout")
                .amount("900.00")
                .currency("USD")
                .returnTo("https://app.example.com/back")
                .build());
      case "retrieveStepUp":
        return auth.retrieveStepUp(ACCESS_TOKEN, STEP_UP_ID);
      default:
        throw new AssertionError(
            "authentication.json documents " + method + ": add it to Authentication and here");
    }
  }

  /** The request that performed the documented operation. */
  private static TransportRequest sent(StubIssuer issuer, String method, Operation operation) {
    TransportRequest found = null;
    for (TransportRequest r : issuer.requests) {
      if (r.method().equals(operation.http)
          && operation.pattern.matcher(r.uri().getPath()).matches()) {
        found = r;
      }
    }
    if (found == null) {
      throw new AssertionError(
          method + ": no " + operation.http + " " + operation.path + " was sent");
    }
    return found;
  }

  private static Map<String, String> form(TransportRequest request) {
    String body = new String(request.body(), StandardCharsets.UTF_8);
    Map<String, String> out = new LinkedHashMap<>();
    String type = Headers.of(request.headers()).firstValue("Content-Type").orElse("");
    if (type.startsWith("application/json")) {
      TestJson.parse(body)
          .properties()
          .forEach(field -> out.put(field.getKey(), field.getValue().asText()));
      return out;
    }
    for (String pair : body.split("&")) {
      String[] parts = pair.split("=", 2);
      out.put(
          URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
          parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
    }
    return out;
  }

  private static TransportResponse reply(int status, JsonNode body) {
    if (body.isNull()) {
      return new TransportResponse(status, Headers.empty(), new byte[0]);
    }
    return StubIssuer.json(status, body.toString());
  }

  @Test
  void theModuleReadsTheDocumentedKeySet() {
    JsonNode keys = operations().get("getJwks").examples.get(200).get(0);
    Set<String> kids = new TreeSet<>();
    keys.path("keys").forEach(k -> kids.add(k.path("kid").asText()));
    assertEquals(kids, Authentication.parseKeys((ObjectNode) keys).keySet());
  }
}
