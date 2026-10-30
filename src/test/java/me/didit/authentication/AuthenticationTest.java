package me.didit.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigInteger;
import java.net.ConnectException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import me.didit.core.Headers;
import me.didit.core.TransportResponse;
import me.didit.errors.ApiConnectionException;
import me.didit.errors.ApiTimeoutException;
import me.didit.errors.AuthenticationException;
import me.didit.errors.BadRequestException;
import me.didit.errors.ConfigurationException;
import me.didit.errors.TokenVerificationException;
import me.didit.errors.TokenVerificationException.Reason;
import org.junit.jupiter.api.Test;

final class AuthenticationTest {
  private final Instant now = Instant.parse("2026-11-01T10:00:00Z");
  private final MutableClock clock = new MutableClock(now);
  private final StubIssuer issuer = new StubIssuer();

  private Authentication auth() {
    return Authentication.builder()
        .issuer(StubIssuer.ISSUER)
        .transport(issuer)
        .clock(clock)
        .build();
  }

  private Authentication authWithClient() {
    return Authentication.builder()
        .issuer(StubIssuer.ISSUER + "/")
        .clientId(StubIssuer.APP_ID)
        .transport(issuer)
        .clock(clock)
        .build();
  }

  private String access(ObjectNode claims) {
    return issuer.sign(issuer.header("at+jwt"), claims);
  }

  private Reason reject(Authentication auth, String token) {
    return assertThrows(TokenVerificationException.class, () -> auth.verifyAccessToken(token))
        .reason();
  }

  static final class MutableClock extends Clock {
    Instant instant;

    MutableClock(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }

  @Test
  void acceptsAValidAccessToken() {
    AccessTokenClaims claims = authWithClient().verifyAccessToken(access(issuer.accessClaims(now)));
    assertEquals(StubIssuer.ISSUER, claims.issuer());
    assertEquals("11111111-1111-4111-8111-111111111111", claims.subject());
    assertEquals(List.of(StubIssuer.APP_ID), claims.audience());
    assertEquals(StubIssuer.APP_ID, claims.clientId().orElseThrow());
    assertEquals("22222222-2222-4222-8222-222222222222", claims.sessionId().orElseThrow());
    assertEquals("jti-1", claims.tokenId().orElseThrow());
    assertEquals(now, claims.issuedAt());
    assertEquals(now.plusSeconds(300), claims.expiresAt());
    assertTrue(claims.notBefore().isEmpty());
    assertEquals("user-42", claims.vendorData().orElseThrow());
    assertEquals(List.of("passkey"), claims.authenticationMethods());
    assertEquals("org-1", claims.organization().orElseThrow().id().orElseThrow());
    assertEquals("admin", claims.organization().orElseThrow().role().orElseThrow());
    assertEquals("full_id", claims.verified().orElseThrow().level().orElseThrow());
    assertTrue(claims.verified().orElseThrow().over18().orElseThrow());
    assertTrue(claims.actor().isEmpty());
    assertTrue(claims.field("vendor_data").isPresent());
    assertTrue(claims._json().has("iss"));
    // Each client fetches discovery and the keys once, then verifies from its cache.
    authWithClient().verifyAccessToken(access(issuer.accessClaims(now)));
    Authentication shared = auth();
    shared.verifyAccessToken(access(issuer.accessClaims(now)));
    shared.verifyAccessToken(access(issuer.accessClaims(now)));
    assertEquals(3, issuer.count("GET /.well-known/jwks.json"));
    assertEquals(3, issuer.count("GET /.well-known/openid-configuration"));
  }

  @Test
  void readsAgentAndVerifiedClaims() {
    ObjectNode claims = issuer.accessClaims(now);
    ObjectNode act = claims.putObject("act");
    act.put("sub", "agent-1");
    act.put("agent", "Helper");
    act.putArray("scopes").add("read");
    ObjectNode verified = claims.putObject("verified");
    verified.put("level", "full_id");
    verified.put("over_21", false);
    verified.put("given_name", "Ana");
    verified.put("family_name", "Ruiz");
    verified.put("birthdate", "1990-01-31");
    verified.put("nationality", "ESP");
    verified.put("country", "ESP");
    verified.put("document_verified", true);
    verified.put("liveness", true);
    claims.putArray("aud").add("other").add(StubIssuer.APP_ID);
    claims.putNull("org");
    AccessTokenClaims c = auth().verifyAccessToken(access(claims), StubIssuer.APP_ID);
    assertEquals("agent-1", c.actor().orElseThrow().subject().orElseThrow());
    assertEquals("Helper", c.actor().orElseThrow().agent().orElseThrow());
    assertEquals(List.of("read"), c.actor().orElseThrow().scopes());
    VerifiedClaims v = c.verified().orElseThrow();
    assertFalse(v.over21().orElseThrow());
    assertEquals("Ana", v.givenName().orElseThrow());
    assertEquals("Ruiz", v.familyName().orElseThrow());
    assertEquals("1990-01-31", v.birthdate().orElseThrow());
    assertEquals("ESP", v.nationality().orElseThrow());
    assertEquals("ESP", v.country().orElseThrow());
    assertTrue(v.documentVerified().orElseThrow());
    assertTrue(v.liveness().orElseThrow());
    assertTrue(c.organization().isEmpty());
    assertEquals(List.of("other", StubIssuer.APP_ID), c.audience());
    // toString() leaves identity data out, also inside nested objects.
    String printed = c.toString();
    for (String personal : new String[] {"Ana", "Ruiz", "1990-01-31", "ESP", "user-42"}) {
      assertFalse(printed.contains(personal), personal);
    }
    assertTrue(printed.contains("agent-1"), printed);
    ObjectNode empty = issuer.accessClaims(now);
    empty.putObject("verified");
    assertTrue(auth().verifyAccessToken(access(empty)).verified().isEmpty());
  }

  @Test
  void rejectsOtherAlgorithms() {
    ObjectNode claims = issuer.accessClaims(now);
    for (String alg : new String[] {"none", "HS256", "RS256", "ES384", "es256"}) {
      ObjectNode header = issuer.header("at+jwt");
      header.put("alg", alg);
      assertEquals(Reason.UNSUPPORTED_ALGORITHM, reject(auth(), issuer.sign(header, claims)), alg);
    }
    ObjectNode noAlg = issuer.header("at+jwt");
    noAlg.remove("alg");
    assertEquals(Reason.UNSUPPORTED_ALGORITHM, reject(auth(), issuer.sign(noAlg, claims)));
    // alg none with an empty signature never reaches the key lookup.
    String b64header =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                "{\"alg\":\"none\",\"typ\":\"at+jwt\"}".getBytes(StandardCharsets.UTF_8));
    String b64claims =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8));
    assertEquals(Reason.MALFORMED, reject(auth(), b64header + "." + b64claims + "."));
    assertEquals(0, issuer.count("GET /.well-known/jwks.json"));
  }

  @Test
  void checksTheTokenType() {
    ObjectNode claims = issuer.accessClaims(now);
    assertEquals(Reason.INVALID_TYPE, reject(auth(), issuer.sign(issuer.header("JWT"), claims)));
    ObjectNode noTyp = issuer.header("at+jwt");
    noTyp.remove("typ");
    assertEquals(Reason.INVALID_TYPE, reject(auth(), issuer.sign(noTyp, claims)));
    assertEquals(
        StubIssuer.ISSUER,
        auth()
            .verifyAccessToken(issuer.sign(issuer.header("application/AT+JWT"), claims))
            .issuer());
    // An access token is not an ID token.
    TokenVerificationException e =
        assertThrows(
            TokenVerificationException.class,
            () -> authWithClient().verifyIdToken(access(claims), null));
    assertEquals(Reason.INVALID_TYPE, e.reason());
  }

  @Test
  void checksIssuerAudienceAndTimes() {
    ObjectNode wrongIssuer = issuer.accessClaims(now);
    wrongIssuer.put("iss", "https://evil.auth.didit.me");
    assertEquals(Reason.INVALID_ISSUER, reject(auth(), access(wrongIssuer)));
    ObjectNode slash = issuer.accessClaims(now);
    slash.put("iss", StubIssuer.ISSUER + "/");
    assertEquals(Reason.INVALID_ISSUER, reject(auth(), access(slash)));

    ObjectNode otherAudience = issuer.accessClaims(now);
    otherAudience.put("aud", "another-app");
    assertEquals(Reason.INVALID_AUDIENCE, reject(authWithClient(), access(otherAudience)));
    assertEquals(
        "another-app",
        auth().verifyAccessToken(access(otherAudience)).audience().get(0),
        "no audience check without an expected audience");
    assertEquals(
        Reason.INVALID_AUDIENCE,
        assertThrows(
                TokenVerificationException.class,
                () -> auth().verifyAccessToken(access(otherAudience), "mine"))
            .reason());

    ObjectNode expired = issuer.accessClaims(now.minusSeconds(400));
    assertEquals(Reason.EXPIRED, reject(auth(), access(expired)));
    ObjectNode withinLeeway = issuer.accessClaims(now.minusSeconds(330));
    assertEquals(StubIssuer.ISSUER, auth().verifyAccessToken(access(withinLeeway)).issuer());

    ObjectNode future = issuer.accessClaims(now);
    future.put("nbf", now.getEpochSecond() + 120);
    assertEquals(Reason.NOT_YET_VALID, reject(auth(), access(future)));
    ObjectNode nbfOk = issuer.accessClaims(now);
    nbfOk.put("nbf", now.getEpochSecond() + 30);
    assertEquals(
        now.plusSeconds(30), auth().verifyAccessToken(access(nbfOk)).notBefore().orElseThrow());
    ObjectNode nbfString = issuer.accessClaims(now);
    nbfString.put("nbf", "soon");
    assertEquals(Reason.MALFORMED, reject(auth(), access(nbfString)));

    ObjectNode issuedLater = issuer.accessClaims(now.plusSeconds(120));
    assertEquals(Reason.INVALID_ISSUED_AT, reject(auth(), access(issuedLater)));

    for (String claim : new String[] {"exp", "iat", "sub"}) {
      ObjectNode missing = issuer.accessClaims(now);
      missing.remove(claim);
      assertEquals(Reason.MISSING_CLAIM, reject(auth(), access(missing)), claim);
    }
    ObjectNode expString = issuer.accessClaims(now);
    expString.put("exp", "later");
    assertEquals(Reason.MISSING_CLAIM, reject(auth(), access(expString)));
  }

  @Test
  void rejectsUnknownKeysAndForgedSignatures() {
    ObjectNode claims = issuer.accessClaims(now);
    ObjectNode header = issuer.header("at+jwt");
    header.put("kid", "k_unknown");
    Authentication auth = auth();
    assertEquals(Reason.UNKNOWN_KEY, reject(auth, issuer.sign(header, claims)));
    // A second unknown kid within a minute does not refetch the keys.
    assertEquals(Reason.UNKNOWN_KEY, reject(auth, issuer.sign(header, claims)));
    assertEquals(1, issuer.count("GET /.well-known/jwks.json"));
    // After a minute, a new key published by the issuer is picked up.
    clock.instant = now.plusSeconds(61);
    issuer.routes.put(
        "GET /.well-known/jwks.json",
        r -> {
          ObjectNode doc = issuer.jwks();
          doc.withArray("keys")
              .add(StubIssuer.jwk("k_unknown", (ECPublicKey) issuer.keys.getPublic()));
          return StubIssuer.json(200, doc, "max-age=300");
        });
    assertEquals(
        StubIssuer.ISSUER,
        auth.verifyAccessToken(issuer.sign(header, issuer.accessClaims(clock.instant))).issuer());
    assertEquals(2, issuer.count("GET /.well-known/jwks.json"));

    // Another key under the known kid.
    String forged = StubIssuer.sign(issuer.header("at+jwt"), claims, issuer.otherKeys);
    assertEquals(Reason.INVALID_SIGNATURE, reject(auth(), forged));
    // A tampered payload.
    String token = access(claims);
    String[] parts = token.split("\\.");
    ObjectNode tampered = claims.deepCopy();
    tampered.put("sub", "someone-else");
    String forgedPayload =
        parts[0]
            + "."
            + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(tampered.toString().getBytes(StandardCharsets.UTF_8))
            + "."
            + parts[2];
    assertEquals(Reason.INVALID_SIGNATURE, reject(auth(), forgedPayload));
    // A truncated signature.
    assertEquals(
        Reason.INVALID_SIGNATURE,
        reject(auth(), parts[0] + "." + parts[1] + "." + parts[2].substring(0, 40)));
    // A DER signature from the right key: JWS needs the 64-byte r || s form.
    assertEquals(Reason.INVALID_SIGNATURE, reject(auth(), derSigned(parts[0] + "." + parts[1])));
    // r or s outside [1, n-1], which unpatched JDKs (CVE-2022-21449) accepted for r = s = 0.
    BigInteger n = ((ECPublicKey) issuer.keys.getPublic()).getParams().getOrder();
    byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
    byte[] s = java.util.Arrays.copyOfRange(signature, 32, 64);
    for (byte[] forgedSignature :
        new byte[][] {
          new byte[64],
          concat(new byte[32], s),
          concat(fixed(n), s),
          concat(fixed(n.add(BigInteger.ONE)), s)
        }) {
      assertEquals(
          Reason.INVALID_SIGNATURE,
          reject(
              auth(),
              parts[0]
                  + "."
                  + parts[1]
                  + "."
                  + Base64.getUrlEncoder().withoutPadding().encodeToString(forgedSignature)));
    }
  }

  private static byte[] fixed(BigInteger value) {
    byte[] raw = value.toByteArray();
    byte[] out = new byte[32];
    int copy = Math.min(raw.length, 32);
    System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
    return out;
  }

  private static byte[] concat(byte[] a, byte[] b) {
    byte[] out = java.util.Arrays.copyOf(a, a.length + b.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }

  private String derSigned(String input) {
    try {
      java.security.Signature signer = java.security.Signature.getInstance("SHA256withECDSA");
      signer.initSign(issuer.keys.getPrivate());
      signer.update(input.getBytes(StandardCharsets.US_ASCII));
      return input + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void rejectsMalformedTokens() {
    String token = access(issuer.accessClaims(now));
    String[] parts = token.split("\\.");
    Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
    for (String bad :
        new String[] {
          "",
          "abc",
          parts[0] + "." + parts[1],
          token + ".x",
          parts[0] + ".%%%." + parts[2],
          b64.encodeToString("not json".getBytes(StandardCharsets.UTF_8))
              + "."
              + parts[1]
              + "."
              + parts[2],
          b64.encodeToString(
                  "{\"alg\":\"ES256\",\"alg\":\"ES256\",\"typ\":\"at+jwt\",\"kid\":\"k\"}"
                      .getBytes(StandardCharsets.UTF_8))
              + "."
              + parts[1]
              + "."
              + parts[2],
          "a".repeat(16385)
        }) {
      assertEquals(
          Reason.MALFORMED, reject(auth(), bad), bad.length() > 60 ? bad.substring(0, 60) : bad);
    }
    ObjectNode crit = issuer.header("at+jwt");
    crit.putArray("crit").add("exp");
    assertEquals(Reason.MALFORMED, reject(auth(), issuer.sign(crit, issuer.accessClaims(now))));
    ObjectNode noKid = issuer.header("at+jwt");
    noKid.remove("kid");
    assertEquals(Reason.MALFORMED, reject(auth(), issuer.sign(noKid, issuer.accessClaims(now))));
    // A payload that is not an object, correctly signed.
    String arrayPayload = signRaw(issuer.header("at+jwt").toString(), "[1]");
    assertEquals(Reason.MALFORMED, reject(auth(), arrayPayload));
    // A signature part whose length leaves a remainder of 1 is not base64url at all; the async
    // variant reports it through the future, never by throwing.
    long fetches = issuer.count("GET /.well-known/jwks.json");
    for (String signature : new String[] {"A", parts[2].substring(0, 85)}) {
      String bad = parts[0] + "." + parts[1] + "." + signature;
      assertEquals(1, signature.length() % 4);
      assertEquals(Reason.MALFORMED, reject(auth(), bad));
      CompletableFuture<AccessTokenClaims> future = auth().verifyAccessTokenAsync(bad, null);
      assertTrue(future.isCompletedExceptionally());
      java.util.concurrent.ExecutionException e =
          assertThrows(java.util.concurrent.ExecutionException.class, future::get);
      assertEquals(Reason.MALFORMED, ((TokenVerificationException) e.getCause()).reason());
    }
    assertEquals(fetches, issuer.count("GET /.well-known/jwks.json"));
  }

  @Test
  void verifiesIdTokens() {
    ObjectNode claims = issuer.accessClaims(now);
    claims.remove("client_id");
    claims.put("auth_time", now.getEpochSecond() - 10);
    claims.put("nonce", "nonce-1");
    claims.put("email", "ana@example.com");
    claims.put("email_verified", true);
    claims.put("name", "Ana");
    claims.put("phone_number", "+34600000000");
    String token = issuer.sign(issuer.header("JWT"), claims);
    IdTokenClaims id = authWithClient().verifyIdToken(token, "nonce-1");
    assertEquals("nonce-1", id.nonce().orElseThrow());
    assertEquals(now.minusSeconds(10), id.authTime().orElseThrow());
    assertEquals("ana@example.com", id.email().orElseThrow());
    assertTrue(id.emailVerified());
    assertEquals("Ana", id.name().orElseThrow());
    assertEquals("+34600000000", id.phoneNumber().orElseThrow());
    assertFalse(id.toString().contains("ana@example.com"));
    assertEquals(
        Reason.INVALID_NONCE,
        assertThrows(
                TokenVerificationException.class,
                () -> authWithClient().verifyIdToken(token, "other"))
            .reason());
    // A refreshed ID token has no nonce: pass null.
    ObjectNode refreshed = claims.deepCopy();
    refreshed.remove("nonce");
    String refreshedToken = issuer.sign(issuer.header("JWT"), refreshed);
    assertTrue(authWithClient().verifyIdToken(refreshedToken, null).nonce().isEmpty());
    assertEquals(
        Reason.INVALID_NONCE,
        assertThrows(
                TokenVerificationException.class,
                () -> authWithClient().verifyIdToken(refreshedToken, "nonce-1"))
            .reason());
    // Without an expected nonce, a token that carries one is refused (no silent opt-out).
    assertEquals(
        Reason.INVALID_NONCE,
        assertThrows(
                TokenVerificationException.class, () -> authWithClient().verifyIdToken(token, null))
            .reason());
    // typ may be absent on an ID token.
    ObjectNode noTyp = issuer.header("JWT");
    noTyp.remove("typ");
    assertEquals(
        "Ana",
        auth()
            .verifyIdToken(issuer.sign(noTyp, claims), "nonce-1", StubIssuer.APP_ID)
            .name()
            .orElseThrow());
    // The audience is required.
    assertThrows(ConfigurationException.class, () -> auth().verifyIdToken(token, "nonce-1"));
    ObjectNode noEmail = claims.deepCopy();
    noEmail.remove("email_verified");
    assertFalse(
        authWithClient()
            .verifyIdToken(issuer.sign(issuer.header("JWT"), noEmail), "nonce-1")
            .emailVerified());
  }

  @Test
  void discoveryRules() {
    issuer.discovery.put("issuer", "https://other.auth.didit.me");
    assertThrows(
        ConfigurationException.class,
        () -> auth().verifyAccessToken(access(issuer.accessClaims(now))));

    StubIssuer second = new StubIssuer();
    second.discovery.put("token_endpoint", "https://evil.example.com/token/");
    Authentication a =
        Authentication.builder().issuer(StubIssuer.ISSUER).transport(second).clock(clock).build();
    assertThrows(ConfigurationException.class, () -> a.exchangeCode("c", "v", "https://app/cb"));

    StubIssuer third = new StubIssuer();
    third.discovery.put("jwks_uri", "http://acme.auth.didit.me/keys");
    Authentication b =
        Authentication.builder().issuer(StubIssuer.ISSUER).transport(third).clock(clock).build();
    assertThrows(
        ConfigurationException.class, () -> b.verifyAccessToken(access(third.accessClaims(now))));

    StubIssuer fourth = new StubIssuer();
    fourth.discovery.remove("token_endpoint");
    fourth.discovery.remove("jwks_uri");
    fourth.routes.put("POST /token/", r -> StubIssuer.json(200, "{\"access_token\":\"a\"}"));
    Authentication c =
        Authentication.builder().issuer(StubIssuer.ISSUER).transport(fourth).clock(clock).build();
    assertEquals("a", c.exchangeCode("code", "verifier", "https://app/cb").accessToken());
    assertEquals(
        StubIssuer.ISSUER,
        c.verifyAccessToken(fourth.sign(fourth.header("at+jwt"), fourth.accessClaims(now)))
            .issuer());
    // Discovery was fetched once and cached for both calls.
    assertEquals(1, fourth.count("GET /.well-known/openid-configuration"));

    // A loopback issuer may use http for its keys, whatever the case of the scheme.
    StubIssuer local = new StubIssuer();
    String upper = "HTTP://localhost:8000";
    local.discovery.put("issuer", upper);
    local.discovery.put("token_endpoint", "http://localhost:8000/token/");
    local.discovery.put("jwks_uri", "http://localhost:8000/.well-known/jwks.json");
    Authentication e = Authentication.builder().issuer(upper).transport(local).clock(clock).build();
    ObjectNode localClaims = local.accessClaims(now);
    localClaims.put("iss", upper);
    assertEquals(
        upper, e.verifyAccessToken(local.sign(local.header("at+jwt"), localClaims)).issuer());

    StubIssuer fifth = new StubIssuer();
    fifth.discovery.remove("issuer");
    Authentication d =
        Authentication.builder().issuer(StubIssuer.ISSUER).transport(fifth).clock(clock).build();
    assertThrows(me.didit.errors.UnexpectedResponseException.class, () -> d.refresh("r"));
  }

  @Test
  void cacheLifetimes() {
    assertEquals(Duration.ofMinutes(5), Authentication.cacheLifetime(Headers.empty()));
    assertEquals(
        Duration.ofSeconds(600),
        Authentication.cacheLifetime(headers("Cache-Control", "public, max-age=600")));
    assertEquals(
        Duration.ofSeconds(500),
        Authentication.cacheLifetime(headers("Cache-Control", "max-age=600", "Age", "100")));
    assertEquals(
        Duration.ofMinutes(1), Authentication.cacheLifetime(headers("Cache-Control", "no-store")));
    assertEquals(
        Duration.ofMinutes(1), Authentication.cacheLifetime(headers("Cache-Control", "max-age=5")));
    assertEquals(
        Duration.ofHours(24),
        Authentication.cacheLifetime(headers("Cache-Control", "max-age=999999")));
    assertEquals(
        Duration.ofMinutes(5),
        Authentication.cacheLifetime(headers("Cache-Control", "max-age=abc", "Age", "x")));
  }

  @Test
  void keysExpireAndInvalidKeysAreSkipped() {
    Authentication auth = auth();
    auth.verifyAccessToken(access(issuer.accessClaims(now)));
    clock.instant = now.plusSeconds(301);
    auth.verifyAccessToken(access(issuer.accessClaims(clock.instant)));
    assertEquals(2, issuer.count("GET /.well-known/jwks.json"));
    ObjectNode doc = issuer.jwks();
    ObjectNode badPoint = StubIssuer.jwk("bad", (ECPublicKey) issuer.keys.getPublic());
    badPoint.put("y", badPoint.get("x").asText());
    doc.withArray("keys").add(badPoint);
    ObjectNode rsa = doc.withArray("keys").addObject();
    rsa.put("kty", "RSA");
    rsa.put("kid", "rsa");
    ObjectNode enc = StubIssuer.jwk("enc", (ECPublicKey) issuer.keys.getPublic());
    enc.put("use", "enc");
    doc.withArray("keys").add(enc);
    ObjectNode shortX = StubIssuer.jwk("short", (ECPublicKey) issuer.keys.getPublic());
    shortX.put("x", "AAAA");
    doc.withArray("keys").add(shortX);
    ObjectNode p384 = doc.withArray("keys").addObject();
    p384.put("kty", "EC");
    p384.put("crv", "P-384");
    p384.put("kid", "p384");
    p384.put("x", Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[48]));
    p384.put("y", Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[48]));
    ObjectNode noCurve = StubIssuer.jwk("no-curve", (ECPublicKey) issuer.keys.getPublic());
    noCurve.remove("crv");
    doc.withArray("keys").add(noCurve);
    Map<String, ECPublicKey> keys = Authentication.parseKeys(doc);
    assertEquals(Set.of(issuer.kid), keys.keySet());
    assertThrows(
        me.didit.errors.UnexpectedResponseException.class,
        () -> Authentication.parseKeys(StubIssuer.parse("{}")));
  }

  @Test
  void concurrentCallsShareOneFetch() throws Exception {
    CompletableFuture<TransportResponse> keySet = new CompletableFuture<>();
    issuer.held.put("GET /.well-known/jwks.json", keySet);
    Authentication auth = auth();
    String token = access(issuer.accessClaims(now));
    List<CompletableFuture<AccessTokenClaims>> waiting = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      waiting.add(auth.verifyAccessTokenAsync(token, null));
    }
    // A token with an unknown key id joins the same fetch instead of starting another.
    ObjectNode unknown = issuer.header("at+jwt");
    unknown.put("kid", "k_other");
    CompletableFuture<AccessTokenClaims> other =
        auth.verifyAccessTokenAsync(issuer.sign(unknown, issuer.accessClaims(now)), null);
    assertEquals(1, issuer.count("GET /.well-known/openid-configuration"));
    assertEquals(1, issuer.count("GET /.well-known/jwks.json"));
    assertFalse(waiting.get(0).isDone());
    issuer.held.clear();
    keySet.complete(StubIssuer.json(200, issuer.jwks(), "max-age=300"));
    for (CompletableFuture<AccessTokenClaims> f : waiting) {
      assertEquals(StubIssuer.ISSUER, f.get().issuer());
    }
    ExecutionException e = assertThrows(ExecutionException.class, other::get);
    assertEquals(Reason.UNKNOWN_KEY, ((TokenVerificationException) e.getCause()).reason());
    assertEquals(1, issuer.count("GET /.well-known/jwks.json"));

    // Two token calls wait on one discovery request.
    StubIssuer second = new StubIssuer();
    CompletableFuture<TransportResponse> discovery = new CompletableFuture<>();
    second.held.put("GET /.well-known/openid-configuration", discovery);
    second.routes.put("POST /token/", r -> StubIssuer.json(200, "{\"access_token\":\"a\"}"));
    Authentication a =
        Authentication.builder().issuer(StubIssuer.ISSUER).transport(second).clock(clock).build();
    CompletableFuture<TokenResponse> one = a.refreshAsync("r1");
    CompletableFuture<TokenResponse> two = a.refreshAsync("r2");
    assertEquals(1, second.count("GET /.well-known/openid-configuration"));
    second.held.clear();
    discovery.complete(StubIssuer.json(200, second.discovery, "max-age=300"));
    assertEquals("a", one.get().accessToken());
    assertEquals("a", two.get().accessToken());
    assertEquals(2, second.count("POST /token/"));
  }

  @Test
  void aFailedKeyFetchIsNotRepeatedForFiveSeconds() {
    issuer.routes.put(
        "GET /.well-known/jwks.json", r -> StubIssuer.json(503, "{\"detail\":\"down\"}"));
    Authentication auth = auth();
    String token = access(issuer.accessClaims(now));
    ObjectNode unknown = issuer.header("at+jwt");
    unknown.put("kid", "k_random");
    String randomKid = issuer.sign(unknown, issuer.accessClaims(now));
    assertThrows(
        me.didit.errors.InternalServerException.class, () -> auth.verifyAccessToken(token));
    // During the backoff, neither a known nor a random key id reaches the issuer.
    clock.instant = now.plusSeconds(4);
    assertThrows(
        me.didit.errors.InternalServerException.class, () -> auth.verifyAccessToken(token));
    assertThrows(
        me.didit.errors.InternalServerException.class, () -> auth.verifyAccessToken(randomKid));
    assertEquals(1, issuer.count("GET /.well-known/jwks.json"));
    // Once it has passed, the next verification fetches again and succeeds.
    clock.instant = now.plus(Authentication.FAILED_FETCH_BACKOFF);
    issuer.routes.put(
        "GET /.well-known/jwks.json", r -> StubIssuer.json(200, issuer.jwks(), "max-age=300"));
    assertEquals(StubIssuer.ISSUER, auth.verifyAccessToken(token).issuer());
    assertEquals(2, issuer.count("GET /.well-known/jwks.json"));

    // Expired keys that cannot be refreshed follow the same rule.
    Instant later = clock.instant.plusSeconds(301);
    clock.instant = later;
    issuer.routes.put(
        "GET /.well-known/jwks.json", r -> StubIssuer.json(503, "{\"detail\":\"down\"}"));
    String fresh = access(issuer.accessClaims(later));
    assertThrows(
        me.didit.errors.InternalServerException.class, () -> auth.verifyAccessToken(fresh));
    assertThrows(
        me.didit.errors.InternalServerException.class, () -> auth.verifyAccessToken(fresh));
    assertEquals(3, issuer.count("GET /.well-known/jwks.json"));

    // A discovery outage counts as a failed key fetch too.
    StubIssuer down = new StubIssuer();
    down.routes.put(
        "GET /.well-known/openid-configuration", r -> StubIssuer.json(502, "{\"detail\":\"x\"}"));
    Authentication b =
        Authentication.builder().issuer(StubIssuer.ISSUER).transport(down).clock(clock).build();
    String other = down.sign(down.header("at+jwt"), down.accessClaims(later));
    assertThrows(me.didit.errors.InternalServerException.class, () -> b.verifyAccessToken(other));
    assertThrows(me.didit.errors.InternalServerException.class, () -> b.verifyAccessToken(other));
    assertEquals(1, down.count("GET /.well-known/openid-configuration"));
  }

  @Test
  void exchangesCodesAndRefreshes() {
    issuer.routes.put(
        "POST /token/",
        r ->
            StubIssuer.json(
                200,
                "{\"access_token\":\"at-1\",\"id_token\":\"id-1\",\"refresh_token\":\"rt-1\","
                    + "\"token_type\":\"Bearer\",\"expires_in\":300,\"session_id\":\"s-1\"}"));
    TokenResponse tokens =
        authWithClient().exchangeCode("code-1", "verifier-1", "https://app.example.com/cb");
    assertEquals("at-1", tokens.accessToken());
    assertEquals("id-1", tokens.idToken().orElseThrow());
    assertEquals("rt-1", tokens.refreshToken().orElseThrow());
    assertEquals("Bearer", tokens.tokenType().orElseThrow());
    assertEquals(300L, tokens.expiresIn().orElseThrow());
    assertEquals("s-1", tokens.sessionId().orElseThrow());
    assertFalse(tokens.toString().contains("at-1"));
    Map<String, String> form = form(issuer.last("POST /token/").body());
    assertEquals("authorization_code", form.get("grant_type"));
    assertEquals("code-1", form.get("code"));
    assertEquals("verifier-1", form.get("code_verifier"));
    assertEquals("https://app.example.com/cb", form.get("redirect_uri"));
    assertEquals(StubIssuer.APP_ID, form.get("client_id"));
    assertEquals(
        "application/x-www-form-urlencoded",
        issuer.last("POST /token/").headers().get("Content-Type").get(0));

    auth().refresh("rt-1");
    Map<String, String> refresh = form(issuer.last("POST /token/").body());
    assertEquals("refresh_token", refresh.get("grant_type"));
    assertEquals("rt-1", refresh.get("refresh_token"));
    assertNull(refresh.get("client_id"));

    issuer.routes.put("POST /token/", r -> StubIssuer.json(400, "{\"error\":\"invalid_grant\"}"));
    BadRequestException e =
        assertThrows(BadRequestException.class, () -> auth().refresh("rt-used"));
    assertTrue(e.getMessage().contains("invalid_grant"), e.getMessage());
    assertFalse(e.getMessage().contains("rt-used"));
    issuer.routes.put(
        "POST /token/",
        r -> StubIssuer.json(400, "{\"error\":\"mfa_required\",\"error_description\":\"MFA\"}"));
    assertTrue(
        assertThrows(BadRequestException.class, () -> auth().refresh("r"))
            .getMessage()
            .contains("mfa_required: MFA"));
    // Token requests are never retried: resending a refresh token would revoke the session.
    long sent = issuer.count("POST /token/");
    issuer.routes.put("POST /token/", r -> StubIssuer.json(500, "oops"));
    assertThrows(me.didit.errors.InternalServerException.class, () -> auth().refresh("r"));
    issuer.routes.put("POST /token/", r -> StubIssuer.json(503, "{}"));
    assertThrows(
        me.didit.errors.InternalServerException.class,
        () -> auth().exchangeCode("c", "v", "https://app.example.com/cb"));
    issuer.held.put(
        "POST /token/", CompletableFuture.failedFuture(new ConnectException("connection reset")));
    assertThrows(ApiConnectionException.class, () -> auth().refresh("r"));
    assertEquals(sent + 3, issuer.count("POST /token/"));
  }

  @Test
  void sessionsAndStepUps() {
    issuer.routes.put(
        "GET /session/",
        r ->
            StubIssuer.json(
                200,
                "{\"sub\":\"u-1\",\"sid\":\"s-1\",\"application_id\":\"app\",\"vendor_data\":\"v\","
                    + "\"amr\":[\"password\"],\"mfa_verified\":true,\"email\":\"e@example.com\","
                    + "\"email_verified\":true,\"phone\":\"+1\",\"verified\":null,\"verified_identity\":null,"
                    + "\"expires_at\":\"2026-11-02T10:00:00Z\",\"post_logout_redirect_uri\":\"https://app/\","
                    + "\"organizations\":[{\"organization_id\":\"o-1\",\"organization__name\":\"Acme\","
                    + "\"role\":\"admin\",\"custom_role\":{\"key\":\"ops\",\"permissions\":[]}}]}"));
    AuthenticationSession session = auth().getSession("at-1");
    assertEquals("Bearer at-1", issuer.last("GET /session/").headers().get("Authorization").get(0));
    assertEquals("u-1", session.subject().orElseThrow());
    assertEquals("s-1", session.sessionId().orElseThrow());
    assertEquals("app", session.applicationId().orElseThrow());
    assertEquals("v", session.vendorData().orElseThrow());
    assertEquals(List.of("password"), session.authenticationMethods());
    assertTrue(session.mfaVerified());
    assertEquals("e@example.com", session.email().orElseThrow());
    assertTrue(session.emailVerified());
    assertEquals("+1", session.phone().orElseThrow());
    assertTrue(session.verified().isEmpty());
    assertTrue(session.verifiedIdentity().isEmpty());
    assertEquals(2026, session.expiresAt().orElseThrow().getYear());
    assertEquals("https://app/", session.postLogoutRedirectUri().orElseThrow());
    AuthenticationSession.Organization org = session.organizations().get(0);
    assertEquals("o-1", org.organizationId().orElseThrow());
    assertEquals("Acme", org.organizationName().orElseThrow());
    assertEquals("admin", org.role().orElseThrow());
    assertEquals("ops", org.customRole().orElseThrow().get("key").asText());

    issuer.routes.put("GET /session/", r -> StubIssuer.json(401, "{\"detail\":\"invalid_token\"}"));
    assertThrows(AuthenticationException.class, () -> auth().getSession("expired"));

    issuer.routes.put(
        "DELETE /session/",
        r -> new me.didit.core.TransportResponse(204, Headers.empty(), new byte[0]));
    auth().revokeSession("at-1");
    assertEquals(1, issuer.count("DELETE /session/"));
    // Not retried on a 5xx either.
    issuer.routes.put("DELETE /session/", r -> StubIssuer.json(503, "{\"detail\":\"busy\"}"));
    assertThrows(me.didit.errors.InternalServerException.class, () -> auth().revokeSession("at-1"));
    assertEquals(2, issuer.count("DELETE /session/"));

    issuer.routes.put("POST /step-up/", r -> StubIssuer.json(200, "{\"required\":false}"));
    StepUp notNeeded =
        auth()
            .createStepUp(
                "at-1", StepUpParams.builder("contact").returnTo("https://app/back").build());
    assertFalse(notNeeded.required());
    issuer.routes.put(
        "POST /step-up/",
        r ->
            StubIssuer.json(
                201,
                "{\"id\":\"33333333-3333-4333-8333-333333333333\",\"rule\":\"payout\",\"method\":\"liveness\","
                    + "\"status\":\"pending\",\"level\":null,\"amount\":\"250.00\",\"currency\":\"EUR\","
                    + "\"created_at\":\"2026-11-01T10:00:00Z\",\"completed_at\":null,"
                    + "\"expires_at\":\"2026-11-01T10:30:00Z\",\"required\":true,"
                    + "\"verification_url\":\"https://acme.auth.didit.me/step-up/x\",\"expires_in\":1800}"));
    StepUp stepUp =
        auth()
            .createStepUp(
                "at-1",
                StepUpParams.builder("payout")
                    .amount(new java.math.BigDecimal("250.00"))
                    .currency("EUR")
                    .returnTo("https://app/back")
                    .build());
    assertEquals(
        "{\"action\":\"payout\",\"amount\":\"250.00\",\"currency\":\"EUR\",\"return_to\":\"https://app/back\"}",
        new String(issuer.last("POST /step-up/").body(), StandardCharsets.UTF_8));
    assertTrue(stepUp.required());
    assertFalse(stepUp.isApproved());
    assertEquals("33333333-3333-4333-8333-333333333333", stepUp.id().orElseThrow());
    assertEquals("payout", stepUp.rule().orElseThrow());
    assertEquals("liveness", stepUp.method().orElseThrow());
    assertEquals("pending", stepUp.status().orElseThrow());
    assertTrue(stepUp.level().isEmpty());
    assertEquals("250.00", stepUp.amount().orElseThrow());
    assertEquals("EUR", stepUp.currency().orElseThrow());
    assertEquals(1800L, stepUp.expiresIn().orElseThrow());
    assertTrue(stepUp.verificationUrl().isPresent());
    assertTrue(stepUp.createdAt().isPresent());
    assertTrue(stepUp.completedAt().isEmpty());
    assertTrue(stepUp.expiresAt().isPresent());

    issuer.routes.put(
        "GET /step-up/33333333-3333-4333-8333-333333333333/",
        r ->
            StubIssuer.json(
                200, "{\"id\":\"33333333-3333-4333-8333-333333333333\",\"status\":\"approved\"}"));
    assertTrue(auth().retrieveStepUp("at-1", "33333333-3333-4333-8333-333333333333").isApproved());
    for (String bad : new String[] {"", ".", ".."}) {
      assertThrows(ConfigurationException.class, () -> auth().retrieveStepUp("at-1", bad));
    }
    assertThrows(
        IllegalArgumentException.class, () -> StepUpParams.builder("payout").amount("12,5"));
    assertEquals(
        404,
        assertThrows(
                me.didit.errors.NotFoundException.class, () -> auth().retrieveStepUp("at-1", "a/b"))
            .statusCode());
    assertTrue(
        issuer.requests.stream().anyMatch(r -> r.uri().getRawPath().equals("/step-up/a%2Fb/")));
    // Never sends the API key or an Origin header.
    for (me.didit.core.TransportRequest r : issuer.requests) {
      assertFalse(r.headers().containsKey("x-api-key"));
      assertFalse(r.headers().containsKey("Origin"));
      assertTrue(r.headers().get("User-Agent").get(0).startsWith("didit-java/"));
    }
  }

  @Test
  void asyncVariants() throws Exception {
    issuer.routes.put("POST /token/", r -> StubIssuer.json(200, "{\"access_token\":\"a\"}"));
    issuer.routes.put("GET /session/", r -> StubIssuer.json(200, "{\"sub\":\"u\"}"));
    issuer.routes.put(
        "DELETE /session/",
        r -> new me.didit.core.TransportResponse(204, Headers.empty(), new byte[0]));
    issuer.routes.put("POST /step-up/", r -> StubIssuer.json(200, "{\"required\":false}"));
    issuer.routes.put("GET /step-up/x/", r -> StubIssuer.json(200, "{\"status\":\"declined\"}"));
    Authentication a = authWithClient();
    assertEquals("a", a.exchangeCodeAsync("c", "v", "r").get().accessToken());
    assertEquals("a", a.refreshAsync("r").get().accessToken());
    assertEquals("u", a.getSessionAsync("t").get().subject().orElseThrow());
    a.revokeSessionAsync("t").get();
    assertFalse(a.createStepUpAsync("t", StepUpParams.builder("bank").build()).get().required());
    assertFalse(a.retrieveStepUpAsync("t", "x").get().isApproved());
    assertEquals(
        StubIssuer.ISSUER,
        a.verifyAccessTokenAsync(access(issuer.accessClaims(now)), null).get().issuer());
  }

  @Test
  void connectionFailures() {
    Authentication refused =
        Authentication.builder()
            .issuer(StubIssuer.ISSUER)
            .transport(r -> CompletableFuture.failedFuture(new ConnectException("refused")))
            .build();
    assertThrows(ApiConnectionException.class, () -> refused.getSession("t"));
    Authentication slow =
        Authentication.builder()
            .issuer(StubIssuer.ISSUER)
            .timeout(Duration.ofMillis(50))
            .transport(r -> new CompletableFuture<>())
            .build();
    assertThrows(ApiTimeoutException.class, () -> slow.getSession("t"));
    Authentication broken =
        Authentication.builder()
            .issuer(StubIssuer.ISSUER)
            .transport(
                r -> {
                  throw new IllegalStateException("bug");
                })
            .build();
    assertThrows(ApiConnectionException.class, () -> broken.refresh("r"));
    Authentication notJson =
        Authentication.builder()
            .issuer(StubIssuer.ISSUER)
            .transport(r -> CompletableFuture.completedFuture(StubIssuer.json(200, "[1]")))
            .build();
    assertThrows(me.didit.errors.UnexpectedResponseException.class, () -> notJson.getSession("t"));
    Authentication failingRead =
        Authentication.builder()
            .issuer(StubIssuer.ISSUER)
            .transport(
                r ->
                    CompletableFuture.completedFuture(
                        new me.didit.core.TransportResponse(
                            200,
                            Headers.empty(),
                            new java.io.InputStream() {
                              @Override
                              public int read() throws IOException {
                                throw new IOException("reset");
                              }
                            })))
            .build();
    assertThrows(ApiConnectionException.class, () -> failingRead.getSession("t"));
  }

  @Test
  void helpers() throws Exception {
    Pkce pkce = Authentication.createPkce();
    assertEquals(43, pkce.verifier().length());
    assertEquals("S256", pkce.method());
    byte[] digest =
        MessageDigest.getInstance("SHA-256")
            .digest(pkce.verifier().getBytes(StandardCharsets.US_ASCII));
    assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(digest), pkce.challenge());
    assertFalse(pkce.toString().contains(pkce.verifier()));
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 20; i++) {
      String state = Authentication.createState();
      String nonce = Authentication.createNonce();
      assertEquals(43, state.length());
      assertTrue(state.matches("[A-Za-z0-9_-]+"));
      assertTrue(seen.add(state));
      assertTrue(seen.add(nonce));
    }
  }

  @Test
  void publishableKeysAndIssuers() {
    String live =
        "pk_live_"
            + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("https://acme.auth.didit.me/".getBytes(StandardCharsets.UTF_8));
    PublishableKey key = PublishableKey.parse(" " + live + " ");
    assertEquals("https://acme.auth.didit.me", key.issuer());
    assertTrue(key.isLive());
    assertTrue(key.toString().contains("acme"));
    assertEquals("https://acme.auth.didit.me", Authentication.fromPublishableKey(live).issuer());
    String test =
        "pk_test_"
            + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("http://localhost:8000".getBytes(StandardCharsets.UTF_8));
    assertFalse(PublishableKey.parse(test).isLive());
    String liveHttp =
        "pk_live_"
            + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("http://localhost:8000".getBytes(StandardCharsets.UTF_8));
    assertThrows(ConfigurationException.class, () -> PublishableKey.parse(liveHttp));
    for (String bad :
        new String[] {
          "sk_live_abc",
          "pk_live_",
          "pk_live_a",
          "pk_live_ab+c",
          "pk_test_"
              + Base64.getUrlEncoder()
                  .withoutPadding()
                  .encodeToString(new byte[] {(byte) 0xff, (byte) 0xfe})
        }) {
      assertThrows(ConfigurationException.class, () -> PublishableKey.parse(bad), bad);
    }
    assertEquals(
        "https://acme.auth.didit.me",
        Authentication.create("https://acme.auth.didit.me//").issuer());
    assertEquals(
        "http://app.localhost:8000", Authentication.create("http://app.localhost:8000").issuer());
    assertEquals("http://127.0.0.1:8000", Authentication.create("http://127.0.0.1:8000").issuer());
    for (String bad :
        new String[] {
          "http://acme.auth.didit.me",
          "ftp://acme.auth.didit.me",
          "https://user@acme.auth.didit.me",
          "https://acme.auth.didit.me?x=1",
          "https://acme.auth.didit.me#f",
          "https://acme .auth.didit.me",
          "not a url",
          "acme.auth.didit.me"
        }) {
      assertThrows(ConfigurationException.class, () -> Authentication.create(bad), bad);
    }
    assertThrows(ConfigurationException.class, () -> Authentication.builder().build());
    assertThrows(ConfigurationException.class, () -> Authentication.builder().clientId(""));
    assertThrows(
        ConfigurationException.class,
        () -> Authentication.builder().leeway(Duration.ofSeconds(-1)));
    assertThrows(
        ConfigurationException.class, () -> Authentication.builder().timeout(Duration.ZERO));
    assertTrue(Authentication.create("https://acme.auth.didit.me").toString().contains("acme"));
    assertEquals(
        "https://acme.auth.didit.me",
        Authentication.builder()
            .issuer("https://acme.auth.didit.me")
            .httpClient(
                java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                    .build())
            .leeway(Duration.ZERO)
            .build()
            .issuer());
  }

  private String signRaw(String header, String payload) {
    Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
    String input =
        b64.encodeToString(header.getBytes(StandardCharsets.UTF_8))
            + "."
            + b64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    try {
      java.security.Signature signer =
          java.security.Signature.getInstance("SHA256withECDSAinP1363Format");
      signer.initSign(issuer.keys.getPrivate());
      signer.update(input.getBytes(StandardCharsets.US_ASCII));
      return input + "." + b64.encodeToString(signer.sign());
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Headers headers(String... pairs) {
    Map<String, List<String>> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put(pairs[i], List.of(pairs[i + 1]));
    }
    return Headers.of(map);
  }

  private static Map<String, String> form(byte[] body) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String pair : new String(body, StandardCharsets.UTF_8).split("&")) {
      String[] kv = pair.split("=", 2);
      out.put(
          URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
          URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
    }
    return out;
  }
}
