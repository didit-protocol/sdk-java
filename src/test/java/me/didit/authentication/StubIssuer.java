package me.didit.authentication;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import me.didit.core.Headers;
import me.didit.core.HttpTransport;
import me.didit.core.Json;
import me.didit.core.TransportRequest;
import me.didit.core.TransportResponse;
import me.didit.testing.TestJson;

/**
 * A stubbed Didit Authentication issuer: discovery, JWKS with a key pair generated at test time,
 * and scripted runtime responses. No key material is ever stored in the repository.
 */
final class StubIssuer implements HttpTransport {
  static final String ISSUER = "https://acme.auth.didit.me";
  static final String APP_ID = "00000000-0000-4000-8000-0000000000a2";

  final KeyPair keys = generate();
  final KeyPair otherKeys = generate();
  final String kid = "k_test_current";
  final List<TransportRequest> requests = new ArrayList<>();
  final Map<String, Function<TransportRequest, TransportResponse>> routes = new LinkedHashMap<>();
  /** Routes whose response is held until the test completes the future. */
  final Map<String, CompletableFuture<TransportResponse>> held = new LinkedHashMap<>();
  String cacheControl = "public, max-age=300";
  ObjectNode discovery;

  StubIssuer() {
    discovery = Json.nodes().objectNode();
    discovery.put("issuer", ISSUER);
    discovery.put("token_endpoint", ISSUER + "/token/");
    discovery.put("jwks_uri", ISSUER + "/.well-known/jwks.json");
    routes.put("GET /.well-known/openid-configuration", r -> json(200, discovery, cacheControl));
    routes.put("GET /.well-known/jwks.json", r -> json(200, jwks(), cacheControl));
  }

  static KeyPair generate() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
      generator.initialize(new ECGenParameterSpec("secp256r1"));
      return generator.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  ObjectNode jwks() {
    ObjectNode doc = Json.nodes().objectNode();
    doc.putArray("keys").add(jwk(kid, (ECPublicKey) keys.getPublic()));
    return doc;
  }

  static ObjectNode jwk(String kid, ECPublicKey key) {
    ObjectNode jwk = Json.nodes().objectNode();
    jwk.put("kty", "EC");
    jwk.put("crv", "P-256");
    jwk.put("kid", kid);
    jwk.put("use", "sig");
    jwk.put("alg", "ES256");
    jwk.put("x", coordinate(key.getW().getAffineX()));
    jwk.put("y", coordinate(key.getW().getAffineY()));
    return jwk;
  }

  static String coordinate(BigInteger value) {
    byte[] raw = value.toByteArray();
    byte[] out = new byte[32];
    int copy = Math.min(raw.length, 32);
    System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
  }

  static TransportResponse json(int status, ObjectNode body, String cacheControl) {
    Map<String, List<String>> h = new LinkedHashMap<>();
    h.put("Content-Type", List.of("application/json"));
    h.put("Cache-Control", List.of(cacheControl));
    return new TransportResponse(
        status, Headers.of(h), body.toString().getBytes(StandardCharsets.UTF_8));
  }

  static TransportResponse json(int status, String body) {
    return new TransportResponse(
        status,
        Headers.of(Map.of("Content-Type", List.of("application/json"))),
        body.getBytes(StandardCharsets.UTF_8));
  }

  long count(String route) {
    return requests.stream()
        .filter(r -> route.equals(r.method() + " " + r.uri().getPath()))
        .count();
  }

  TransportRequest last(String route) {
    TransportRequest found = null;
    for (TransportRequest r : requests) {
      if (route.equals(r.method() + " " + r.uri().getPath())) {
        found = r;
      }
    }
    if (found == null) {
      throw new AssertionError("No request " + route);
    }
    return found;
  }

  @Override
  public synchronized CompletableFuture<TransportResponse> send(TransportRequest request) {
    requests.add(request);
    String route = request.method() + " " + request.uri().getPath();
    CompletableFuture<TransportResponse> pending = held.get(route);
    if (pending != null) {
      return pending;
    }
    Function<TransportRequest, TransportResponse> handler = routes.get(route);
    if (handler == null) {
      return CompletableFuture.completedFuture(json(404, "{\"detail\":\"not found\"}"));
    }
    return CompletableFuture.completedFuture(handler.apply(request));
  }

  // -- tokens --------------------------------------------------------------------

  ObjectNode header(String typ) {
    ObjectNode header = Json.nodes().objectNode();
    header.put("alg", "ES256");
    header.put("typ", typ);
    header.put("kid", kid);
    return header;
  }

  ObjectNode accessClaims(Instant now) {
    ObjectNode claims = Json.nodes().objectNode();
    claims.put("iss", ISSUER);
    claims.put("aud", APP_ID);
    claims.put("client_id", APP_ID);
    claims.put("sub", "11111111-1111-4111-8111-111111111111");
    claims.put("sid", "22222222-2222-4222-8222-222222222222");
    claims.put("jti", "jti-1");
    claims.put("iat", now.getEpochSecond());
    claims.put("exp", now.getEpochSecond() + 300);
    claims.put("vendor_data", "user-42");
    claims.putArray("amr").add("passkey");
    ObjectNode org = claims.putObject("org");
    org.put("id", "org-1");
    org.put("role", "admin");
    ObjectNode verified = claims.putObject("verified");
    verified.put("level", "full_id");
    verified.put("over_18", true);
    claims.putNull("act");
    return claims;
  }

  String sign(ObjectNode header, ObjectNode claims) {
    return sign(header, claims, keys);
  }

  static String sign(ObjectNode header, ObjectNode claims, KeyPair pair) {
    Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
    String input =
        b64.encodeToString(header.toString().getBytes(StandardCharsets.UTF_8))
            + "."
            + b64.encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8));
    try {
      Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
      signer.initSign(pair.getPrivate());
      signer.update(input.getBytes(StandardCharsets.US_ASCII));
      return input + "." + b64.encodeToString(signer.sign());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static ObjectNode parse(String json) {
    return (ObjectNode) TestJson.parse(json);
  }
}
