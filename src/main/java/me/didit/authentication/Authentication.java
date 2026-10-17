package me.didit.authentication;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import me.didit.core.Headers;
import me.didit.core.HttpTransport;
import me.didit.core.JdkHttpTransport;
import me.didit.core.Json;
import me.didit.core.TransportResponse;
import me.didit.core.Version;
import me.didit.errors.ApiConnectionException;
import me.didit.errors.ApiStatusException;
import me.didit.errors.ApiTimeoutException;
import me.didit.errors.ConfigurationException;
import me.didit.errors.DiditException;
import me.didit.errors.TokenVerificationException;
import me.didit.errors.TokenVerificationException.Reason;
import me.didit.errors.UnexpectedResponseException;
import org.jspecify.annotations.Nullable;

/**
 * Didit Authentication for your backend (DESIGN 10). It talks to your application's issuer
 * ({@code https://<slug>.auth.didit.me} or your own domain), never with the API key.
 *
 * <pre>{@code
 * Authentication auth = Authentication.fromPublishableKey("pk_live_...");
 * AccessTokenClaims claims = auth.verifyAccessToken(bearerToken);
 * String userId = claims.subject();
 * }</pre>
 *
 * <p>Discovery and the signing keys are fetched lazily and cached as the issuer says (5 minutes
 * today, kept between 1 minute and 24 hours); a token with an unknown key id refetches the keys
 * at most once a minute. Thread-safe: create one per issuer and reuse it.
 *
 * <p>Refreshes of one session must be serialised: each refresh token works once, and reusing a
 * consumed one revokes the session.
 */
public final class Authentication {
  /** The default clock tolerance for {@code exp}, {@code nbf} and {@code iat}. */
  public static final Duration DEFAULT_LEEWAY = Duration.ofMinutes(1);

  /** The default timeout of each call to the issuer. */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

  static final Duration MIN_CACHE = Duration.ofMinutes(1);
  static final Duration MAX_CACHE = Duration.ofHours(24);
  static final Duration DEFAULT_CACHE = Duration.ofMinutes(5);
  static final Duration REFETCH_INTERVAL = Duration.ofMinutes(1);
  private static final int MAX_TOKEN_LENGTH = 16384;
  private static final Pattern BASE64URL = Pattern.compile("[A-Za-z0-9_-]+");
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final ObjectMapper STRICT =
      JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  private final String issuer;
  private final @Nullable String clientId;
  private final Duration leeway;
  private final Duration timeout;
  private final HttpTransport transport;
  private final Clock clock;

  private final Object lock = new Object();
  private @Nullable Discovery discovery;
  private Instant discoveryExpiry = Instant.MIN;
  private Map<String, ECPublicKey> keys = Map.of();
  private Instant keysExpiry = Instant.MIN;
  private Instant lastKeysFetch = Instant.MIN;

  private Authentication(Builder b, String issuer) {
    this.issuer = issuer;
    this.clientId = b.clientId;
    this.leeway = b.leeway;
    this.timeout = b.timeout;
    this.clock = b.clock;
    HttpTransport t = b.transport;
    if (t == null) {
      t =
          b.httpClient != null
              ? new JdkHttpTransport(b.httpClient)
              : JdkHttpTransport.create(timeout);
    }
    this.transport = t;
  }

  /**
   * Authentication for an issuer, with the defaults.
   *
   * @param issuer for example {@code https://acme.auth.didit.me}
   * @return the client
   * @throws ConfigurationException when the issuer is not an https URL (http only for localhost)
   */
  public static Authentication create(String issuer) {
    return builder().issuer(issuer).build();
  }

  /**
   * Authentication for the issuer a publishable key names.
   *
   * @param publishableKey {@code pk_live_...} or {@code pk_test_...}
   * @return the client
   * @throws ConfigurationException when the key is malformed
   */
  public static Authentication fromPublishableKey(String publishableKey) {
    return builder().publishableKey(publishableKey).build();
  }

  /**
   * Returns an empty builder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * The issuer, without a trailing slash.
   *
   * @return the issuer
   */
  public String issuer() {
    return issuer;
  }

  // -- helpers -------------------------------------------------------------------

  /**
   * A new PKCE pair (S256): keep the verifier, send the challenge with the sign-in.
   *
   * @return the pair
   */
  public static Pkce createPkce() {
    String verifier = randomToken();
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
      return new Pkce(verifier, Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  /**
   * A new random {@code state} for the sign-in request (43 URL-safe characters).
   *
   * @return the state
   */
  public static String createState() {
    return randomToken();
  }

  /**
   * A new random {@code nonce} for the sign-in request (43 URL-safe characters).
   *
   * @return the nonce
   */
  public static String createNonce() {
    return randomToken();
  }

  private static String randomToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  // -- token verification -----------------------------------------------------

  /**
   * Verifies an access token; the audience, when the client id is configured, must contain it.
   *
   * @param token the Bearer token
   * @return the verified claims
   * @throws TokenVerificationException when the token is not valid
   * @throws DiditException when the issuer's keys cannot be fetched
   */
  public AccessTokenClaims verifyAccessToken(String token) {
    return await(verifyAccessTokenAsync(token, null));
  }

  /**
   * Verifies an access token for an audience.
   *
   * @param token the Bearer token
   * @param audience the expected audience (your application id), or {@code null} for the
   *     configured client id
   * @return the verified claims
   * @throws TokenVerificationException when the token is not valid
   * @throws DiditException when the issuer's keys cannot be fetched
   */
  public AccessTokenClaims verifyAccessToken(String token, @Nullable String audience) {
    return await(verifyAccessTokenAsync(token, audience));
  }

  /**
   * Verifies an access token for an audience.
   *
   * @param token the Bearer token
   * @param audience the expected audience, or {@code null} for the configured client id
   * @return a future completed with the verified claims
   */
  public CompletableFuture<AccessTokenClaims> verifyAccessTokenAsync(
      String token, @Nullable String audience) {
    return verifyAsync(token, true, audience == null ? clientId : audience, null)
        .thenApply(AccessTokenClaims::new);
  }

  /**
   * Verifies an ID token for the configured client id.
   *
   * @param token the ID token
   * @param nonce the nonce sent with the sign-in, or {@code null} for a token from a refresh
   * @return the verified claims
   * @throws TokenVerificationException when the token is not valid
   * @throws ConfigurationException when no client id is configured
   */
  public IdTokenClaims verifyIdToken(String token, @Nullable String nonce) {
    return verifyIdToken(token, nonce, null);
  }

  /**
   * Verifies an ID token.
   *
   * @param token the ID token
   * @param nonce the nonce sent with the sign-in, or {@code null} for a token from a refresh
   * @param audience your application id, or {@code null} for the configured client id
   * @return the verified claims
   * @throws TokenVerificationException when the token is not valid
   * @throws ConfigurationException when no audience is given or configured
   */
  public IdTokenClaims verifyIdToken(String token, @Nullable String nonce, @Nullable String audience) {
    return await(verifyIdTokenAsync(token, nonce, audience));
  }

  /**
   * Verifies an ID token.
   *
   * @param token the ID token
   * @param nonce the nonce sent with the sign-in, or {@code null} for a token from a refresh
   * @param audience your application id, or {@code null} for the configured client id
   * @return a future completed with the verified claims
   */
  public CompletableFuture<IdTokenClaims> verifyIdTokenAsync(
      String token, @Nullable String nonce, @Nullable String audience) {
    String expected = audience == null ? clientId : audience;
    if (expected == null || expected.isEmpty()) {
      return CompletableFuture.failedFuture(
          new ConfigurationException(
              "Verifying an ID token needs the audience: pass it or set clientId"));
    }
    return verifyAsync(token, false, expected, nonce).thenApply(IdTokenClaims::new);
  }

  private CompletableFuture<ObjectNode> verifyAsync(
      String token, boolean access, @Nullable String audience, @Nullable String nonce) {
    ParsedToken parsed;
    try {
      parsed = ParsedToken.parse(token, access);
    } catch (TokenVerificationException e) {
      return CompletableFuture.failedFuture(e);
    }
    return keyAsync(parsed.kid)
        .thenApply(
            key -> {
              parsed.verifySignature(key);
              ObjectNode claims = parsed.claims();
              checkClaims(claims, audience, nonce, access);
              return claims;
            });
  }

  private void checkClaims(
      ObjectNode claims, @Nullable String audience, @Nullable String nonce, boolean access) {
    JsonNode iss = claims.get("iss");
    if (iss == null || !iss.isTextual() || !iss.textValue().equals(issuer)) {
      throw new TokenVerificationException(Reason.INVALID_ISSUER, "The token's issuer is not " + issuer);
    }
    JsonNode sub = claims.get("sub");
    if (sub == null || !sub.isTextual() || sub.textValue().isEmpty()) {
      throw new TokenVerificationException(Reason.MISSING_CLAIM, "The token has no subject");
    }
    double now = clock.instant().toEpochMilli() / 1000.0;
    double leewaySeconds = leeway.toMillis() / 1000.0;
    JsonNode exp = claims.get("exp");
    JsonNode iat = claims.get("iat");
    if (exp == null || !exp.isNumber()) {
      throw new TokenVerificationException(Reason.MISSING_CLAIM, "The token has no expiry");
    }
    if (iat == null || !iat.isNumber()) {
      throw new TokenVerificationException(Reason.MISSING_CLAIM, "The token has no issue time");
    }
    if (now >= exp.asDouble() + leewaySeconds) {
      throw new TokenVerificationException(Reason.EXPIRED, "The token has expired");
    }
    JsonNode nbf = claims.get("nbf");
    if (nbf != null && !nbf.isNull()) {
      if (!nbf.isNumber()) {
        throw new TokenVerificationException(Reason.MALFORMED, "The token's nbf is not a number");
      }
      if (nbf.asDouble() > now + leewaySeconds) {
        throw new TokenVerificationException(Reason.NOT_YET_VALID, "The token is not valid yet");
      }
    }
    if (iat.asDouble() > now + leewaySeconds) {
      throw new TokenVerificationException(Reason.INVALID_ISSUED_AT, "The token was issued in the future");
    }
    if (audience != null && !TokenClaims.audiences(claims).contains(audience)) {
      throw new TokenVerificationException(
          Reason.INVALID_AUDIENCE, "The token is not for audience " + audience);
    }
    if (!access && nonce != null) {
      JsonNode actual = claims.get("nonce");
      if (actual == null
          || !actual.isTextual()
          || !MessageDigest.isEqual(
              actual.textValue().getBytes(StandardCharsets.UTF_8),
              nonce.getBytes(StandardCharsets.UTF_8))) {
        throw new TokenVerificationException(Reason.INVALID_NONCE, "The token's nonce does not match");
      }
    }
  }

  /** A JWT split into its parts, header checked. */
  static final class ParsedToken {
    final String kid;
    final byte[] signingInput;
    final byte[] signature;
    final String payload;

    private ParsedToken(String kid, byte[] signingInput, byte[] signature, String payload) {
      this.kid = kid;
      this.signingInput = signingInput;
      this.signature = signature;
      this.payload = payload;
    }

    static ParsedToken parse(String token, boolean access) {
      if (token.length() > MAX_TOKEN_LENGTH) {
        throw malformed("The token is too long");
      }
      String[] parts = token.split("\\.", -1);
      if (parts.length != 3) {
        throw malformed("A JWT has three parts");
      }
      for (String part : parts) {
        if (!BASE64URL.matcher(part).matches()) {
          throw malformed("A JWT part is not base64url");
        }
      }
      ObjectNode header = object(decode(parts[0]));
      JsonNode alg = header.get("alg");
      if (alg == null || !alg.isTextual() || !alg.textValue().equals("ES256")) {
        throw new TokenVerificationException(
            Reason.UNSUPPORTED_ALGORITHM, "Only ES256 tokens are accepted");
      }
      if (header.has("crit")) {
        throw malformed("The token has critical header parameters");
      }
      JsonNode typ = header.get("typ");
      String type = typ != null && typ.isTextual() ? typ.textValue().toLowerCase(Locale.ROOT) : null;
      boolean typeOk =
          access
              ? "at+jwt".equals(type) || "application/at+jwt".equals(type)
              : type == null || "jwt".equals(type) || "application/jwt".equals(type);
      if (!typeOk || (typ != null && !typ.isTextual())) {
        throw new TokenVerificationException(
            Reason.INVALID_TYPE,
            access ? "Not an access token (typ at+jwt)" : "Not an ID token (typ JWT)");
      }
      JsonNode kid = header.get("kid");
      if (kid == null || !kid.isTextual() || kid.textValue().isEmpty()) {
        throw malformed("The token has no key id");
      }
      byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
      if (signature.length != 64) {
        throw new TokenVerificationException(
            Reason.INVALID_SIGNATURE, "An ES256 signature is 64 bytes");
      }
      byte[] input = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
      return new ParsedToken(kid.textValue(), input, signature, decode(parts[1]));
    }

    void verifySignature(ECPublicKey key) {
      try {
        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(key);
        verifier.update(signingInput);
        if (!verifier.verify(signature)) {
          throw new TokenVerificationException(Reason.INVALID_SIGNATURE, "The signature does not match");
        }
      } catch (GeneralSecurityException e) {
        throw new TokenVerificationException(Reason.INVALID_SIGNATURE, "The signature does not match");
      }
    }

    ObjectNode claims() {
      return object(payload);
    }

    private static String decode(String part) {
      try {
        byte[] bytes = Base64.getUrlDecoder().decode(part);
        return StandardCharsets.UTF_8
            .newDecoder()
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString();
      } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException e) {
        throw malformed("A JWT part is not base64url UTF-8");
      }
    }

    private static ObjectNode object(String json) {
      try (JsonParser parser = STRICT.createParser(json)) {
        JsonNode node = STRICT.readTree(parser);
        if (node == null || !node.isObject() || parser.nextToken() != null) {
          throw malformed("A JWT part is not a JSON object");
        }
        return (ObjectNode) node;
      } catch (IOException e) {
        throw malformed("A JWT part is not valid JSON");
      }
    }

    private static TokenVerificationException malformed(String message) {
      return new TokenVerificationException(Reason.MALFORMED, message);
    }
  }

  // -- discovery and keys --------------------------------------------------------

  /** The parts of the discovery document the SDK uses. */
  static final class Discovery {
    final URI tokenEndpoint;
    final URI jwksUri;

    Discovery(URI tokenEndpoint, URI jwksUri) {
      this.tokenEndpoint = tokenEndpoint;
      this.jwksUri = jwksUri;
    }
  }

  CompletableFuture<Discovery> discoveryAsync() {
    synchronized (lock) {
      Discovery cached = discovery;
      if (cached != null && clock.instant().isBefore(discoveryExpiry)) {
        return CompletableFuture.completedFuture(cached);
      }
    }
    URI uri = URI.create(issuer + "/.well-known/openid-configuration");
    return send("GET", uri, Map.of(), new byte[0])
        .thenApply(
            response -> {
              ObjectNode doc = jsonObject(response, "the discovery document");
              Discovery parsed = parseDiscovery(doc);
              synchronized (lock) {
                discovery = parsed;
                discoveryExpiry = clock.instant().plus(cacheLifetime(response.headers()));
              }
              return parsed;
            });
  }

  private Discovery parseDiscovery(ObjectNode doc) {
    JsonNode iss = doc.get("issuer");
    if (iss == null || !iss.isTextual()) {
      throw new UnexpectedResponseException("The discovery document has no issuer");
    }
    if (!iss.textValue().equals(issuer)) {
      throw new ConfigurationException(
          "The discovery document names another issuer (" + iss.textValue() + ")");
    }
    URI base = URI.create(issuer + "/");
    URI tokenEndpoint = URI.create(issuer + "/token/");
    JsonNode token = doc.get("token_endpoint");
    if (token != null && token.isTextual()) {
      URI candidate = base.resolve(token.textValue());
      if (!Issuers.origin(candidate).equals(Issuers.origin(base))) {
        throw new ConfigurationException("The token endpoint is not on the issuer's origin");
      }
      tokenEndpoint = candidate;
    }
    URI jwks = URI.create(issuer + "/.well-known/jwks.json");
    JsonNode jwksUri = doc.get("jwks_uri");
    if (jwksUri != null && jwksUri.isTextual()) {
      URI candidate = base.resolve(jwksUri.textValue());
      String scheme = candidate.getScheme() == null ? "" : candidate.getScheme().toLowerCase(Locale.ROOT);
      boolean httpIssuer = issuer.startsWith("http://");
      if (!scheme.equals("https") && !(httpIssuer && scheme.equals("http"))) {
        throw new ConfigurationException("The JWKS URI must use https");
      }
      jwks = candidate;
    }
    return new Discovery(tokenEndpoint, jwks);
  }

  CompletableFuture<ECPublicKey> keyAsync(String kid) {
    Instant now = clock.instant();
    boolean refetch;
    synchronized (lock) {
      ECPublicKey key = keys.get(kid);
      if (key != null && now.isBefore(keysExpiry)) {
        return CompletableFuture.completedFuture(key);
      }
      boolean expired = !now.isBefore(keysExpiry);
      refetch = expired || !now.isBefore(lastKeysFetch.plus(REFETCH_INTERVAL));
      if (!refetch) {
        return CompletableFuture.failedFuture(
            new TokenVerificationException(Reason.UNKNOWN_KEY, "No published key has this key id"));
      }
      lastKeysFetch = now;
    }
    return discoveryAsync()
        .thenCompose(d -> send("GET", d.jwksUri, Map.of(), new byte[0]))
        .thenApply(
            response -> {
              ObjectNode doc = jsonObject(response, "the key set");
              Map<String, ECPublicKey> parsed = parseKeys(doc);
              synchronized (lock) {
                keys = parsed;
                keysExpiry = clock.instant().plus(cacheLifetime(response.headers()));
              }
              ECPublicKey key = parsed.get(kid);
              if (key == null) {
                throw new TokenVerificationException(
                    Reason.UNKNOWN_KEY, "No published key has this key id");
              }
              return key;
            });
  }

  static Map<String, ECPublicKey> parseKeys(ObjectNode doc) {
    Map<String, ECPublicKey> out = new HashMap<>();
    JsonNode list = doc.get("keys");
    if (list == null || !list.isArray()) {
      throw new UnexpectedResponseException("The key set has no keys");
    }
    for (JsonNode jwk : list) {
      if (!jwk.isObject()
          || !"EC".equals(jwk.path("kty").asText())
          || !"P-256".equals(jwk.path("crv").asText())
          || !jwk.path("kid").isTextual()
          || (jwk.has("use") && !"sig".equals(jwk.path("use").asText()))
          || (jwk.has("alg") && !"ES256".equals(jwk.path("alg").asText()))) {
        continue;
      }
      try {
        out.put(jwk.get("kid").textValue(), ecKey(jwk.path("x").asText(), jwk.path("y").asText()));
      } catch (GeneralSecurityException | IllegalArgumentException e) {
        // Skip a key that is not a valid P-256 point.
      }
    }
    return Collections.unmodifiableMap(out);
  }

  static ECPublicKey ecKey(String x, String y) throws GeneralSecurityException {
    byte[] xb = Base64.getUrlDecoder().decode(x);
    byte[] yb = Base64.getUrlDecoder().decode(y);
    if (xb.length != 32 || yb.length != 32) {
      throw new GeneralSecurityException("P-256 coordinates are 32 bytes");
    }
    java.security.AlgorithmParameters params = java.security.AlgorithmParameters.getInstance("EC");
    params.init(new java.security.spec.ECGenParameterSpec("secp256r1"));
    ECParameterSpec spec = params.getParameterSpec(ECParameterSpec.class);
    BigInteger px = new BigInteger(1, xb);
    BigInteger py = new BigInteger(1, yb);
    BigInteger p = ((ECFieldFp) spec.getCurve().getField()).getP();
    BigInteger a = spec.getCurve().getA();
    BigInteger b = spec.getCurve().getB();
    if (px.compareTo(p) >= 0
        || py.compareTo(p) >= 0
        || !py.pow(2).mod(p).equals(px.pow(3).add(a.multiply(px)).add(b).mod(p))) {
      throw new GeneralSecurityException("The point is not on P-256");
    }
    return (ECPublicKey)
        KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(px, py), spec));
  }

  /** {@code max-age} minus {@code Age}; none for no-store; 5 minutes when absent; 1 min..24 h. */
  static Duration cacheLifetime(Headers headers) {
    Duration lifetime = DEFAULT_CACHE;
    String cacheControl = headers.firstValue("Cache-Control").orElse("").toLowerCase(Locale.ROOT);
    boolean noStore = false;
    for (String directive : cacheControl.split(",", -1)) {
      String d = directive.trim();
      if (d.equals("no-store") || d.equals("no-cache")) {
        noStore = true;
      } else if (d.startsWith("max-age=")) {
        try {
          lifetime = Duration.ofSeconds(Long.parseLong(d.substring("max-age=".length()).trim()));
        } catch (NumberFormatException e) {
          lifetime = DEFAULT_CACHE;
        }
      }
    }
    if (noStore) {
      lifetime = Duration.ZERO;
    } else {
      Optional<String> age = headers.firstValue("Age");
      if (age.isPresent()) {
        try {
          lifetime = lifetime.minusSeconds(Long.parseLong(age.get().trim()));
        } catch (NumberFormatException e) {
          // Ignore a malformed Age.
        }
      }
    }
    if (lifetime.compareTo(MIN_CACHE) < 0) {
      return MIN_CACHE;
    }
    return lifetime.compareTo(MAX_CACHE) > 0 ? MAX_CACHE : lifetime;
  }

  // -- runtime calls -------------------------------------------------------------

  /**
   * Exchanges an authorization code for tokens ({@code POST token/}).
   *
   * @param code the {@code code} from the redirect
   * @param codeVerifier the PKCE verifier of this sign-in
   * @param redirectUri the redirect URI of the sign-in request
   * @return the tokens
   * @throws me.didit.errors.BadRequestException with {@code invalid_grant} for a bad or used code
   */
  public TokenResponse exchangeCode(String code, String codeVerifier, String redirectUri) {
    return await(exchangeCodeAsync(code, codeVerifier, redirectUri));
  }

  /**
   * Exchanges an authorization code for tokens.
   *
   * @param code the {@code code} from the redirect
   * @param codeVerifier the PKCE verifier of this sign-in
   * @param redirectUri the redirect URI of the sign-in request
   * @return a future completed with the tokens
   */
  public CompletableFuture<TokenResponse> exchangeCodeAsync(
      String code, String codeVerifier, String redirectUri) {
    Map<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", "authorization_code");
    form.put("code", code);
    form.put("code_verifier", codeVerifier);
    form.put("redirect_uri", redirectUri);
    return token(form);
  }

  /**
   * Exchanges a refresh token for new tokens ({@code POST token/}). Serialise refreshes of one
   * session: reusing a consumed refresh token revokes the session.
   *
   * @param refreshToken the refresh token
   * @return the new tokens, including a new refresh token
   * @throws me.didit.errors.BadRequestException with {@code invalid_grant} for a bad, used or
   *     expired refresh token
   */
  public TokenResponse refresh(String refreshToken) {
    return await(refreshAsync(refreshToken));
  }

  /**
   * Exchanges a refresh token for new tokens.
   *
   * @param refreshToken the refresh token
   * @return a future completed with the new tokens
   */
  public CompletableFuture<TokenResponse> refreshAsync(String refreshToken) {
    Map<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", "refresh_token");
    form.put("refresh_token", refreshToken);
    return token(form);
  }

  private CompletableFuture<TokenResponse> token(Map<String, String> form) {
    if (clientId != null) {
      form.put("client_id", clientId);
    }
    StringBuilder body = new StringBuilder();
    for (Map.Entry<String, String> field : form.entrySet()) {
      if (body.length() > 0) {
        body.append('&');
      }
      body.append(URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8))
          .append('=')
          .append(URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8));
    }
    byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
    return discoveryAsync()
        .thenCompose(
            d ->
                send(
                    "POST",
                    d.tokenEndpoint,
                    Map.of("Content-Type", "application/x-www-form-urlencoded"),
                    bytes))
        .thenApply(response -> new TokenResponse(jsonObject(response, "the token response")));
  }

  /**
   * Reads the session of an access token ({@code GET session/}): a live check that also catches
   * revoked sessions. The runtime rate-limits this per IP; do not call it on every request.
   *
   * @param accessToken the access token
   * @return the session
   * @throws me.didit.errors.AuthenticationException when the token or session is no longer valid
   */
  public AuthenticationSession getSession(String accessToken) {
    return await(getSessionAsync(accessToken));
  }

  /**
   * Reads the session of an access token.
   *
   * @param accessToken the access token
   * @return a future completed with the session
   */
  public CompletableFuture<AuthenticationSession> getSessionAsync(String accessToken) {
    return send("GET", runtime("session/"), bearer(accessToken), new byte[0])
        .thenApply(response -> new AuthenticationSession(jsonObject(response, "the session")));
  }

  /**
   * Signs the user out everywhere the session is used ({@code DELETE session/}).
   *
   * @param accessToken the access token
   */
  public void revokeSession(String accessToken) {
    await(revokeSessionAsync(accessToken));
  }

  /**
   * Signs the user out.
   *
   * @param accessToken the access token
   * @return a future completed when the session is revoked
   */
  public CompletableFuture<Void> revokeSessionAsync(String accessToken) {
    return send("DELETE", runtime("session/"), bearer(accessToken), new byte[0])
        .thenAccept(TransportResponse::close);
  }

  /**
   * Starts a step-up verification before a sensitive action ({@code POST step-up/}). When {@link
   * StepUp#required()} is false no rule applies; otherwise send the user to {@link
   * StepUp#verificationUrl()} and act only once {@link #retrieveStepUp} says approved.
   *
   * @param accessToken the user's access token
   * @param params the action, amount and return URL
   * @return the step-up
   */
  public StepUp createStepUp(String accessToken, StepUpParams params) {
    return await(createStepUpAsync(accessToken, params));
  }

  /**
   * Starts a step-up verification.
   *
   * @param accessToken the user's access token
   * @param params the action, amount and return URL
   * @return a future completed with the step-up
   */
  public CompletableFuture<StepUp> createStepUpAsync(String accessToken, StepUpParams params) {
    ObjectNode body = Json.nodes().objectNode();
    body.put("action", params.action());
    params.amount().ifPresent(a -> body.put("amount", a));
    params.currency().ifPresent(c -> body.put("currency", c));
    params.returnTo().ifPresent(r -> body.put("return_to", r));
    Map<String, String> headers = new LinkedHashMap<>(bearer(accessToken));
    headers.put("Content-Type", "application/json");
    return send("POST", runtime("step-up/"), headers, Json.write(body).getBytes(StandardCharsets.UTF_8))
        .thenApply(response -> new StepUp(jsonObject(response, "the step-up")));
  }

  /**
   * Reads a step-up ({@code GET step-up/{id}/}) with a token of the same session.
   *
   * @param accessToken the user's access token
   * @param stepUpId the step-up id
   * @return the step-up
   */
  public StepUp retrieveStepUp(String accessToken, String stepUpId) {
    return await(retrieveStepUpAsync(accessToken, stepUpId));
  }

  /**
   * Reads a step-up.
   *
   * @param accessToken the user's access token
   * @param stepUpId the step-up id
   * @return a future completed with the step-up
   */
  public CompletableFuture<StepUp> retrieveStepUpAsync(String accessToken, String stepUpId) {
    if (stepUpId.isEmpty() || stepUpId.equals(".") || stepUpId.equals("..")) {
      return CompletableFuture.failedFuture(
          new ConfigurationException("The step-up id must not be empty, '.' or '..'"));
    }
    String encoded = URLEncoder.encode(stepUpId, StandardCharsets.UTF_8).replace("+", "%20");
    return send("GET", runtime("step-up/" + encoded + "/"), bearer(accessToken), new byte[0])
        .thenApply(response -> new StepUp(jsonObject(response, "the step-up")));
  }

  private URI runtime(String path) {
    return URI.create(issuer + "/" + path);
  }

  private static Map<String, String> bearer(String accessToken) {
    Objects.requireNonNull(accessToken, "accessToken");
    return Map.of("Authorization", "Bearer " + accessToken);
  }

  private CompletableFuture<TransportResponse> send(
      String method, URI uri, Map<String, String> extraHeaders, byte[] body) {
    Map<String, List<String>> headers = new LinkedHashMap<>();
    headers.put("Accept", List.of("application/json"));
    headers.put("User-Agent", List.of(userAgent()));
    headers.put("X-Didit-Client", List.of("java/" + Version.VERSION));
    extraHeaders.forEach((k, v) -> headers.put(k, List.of(v)));
    String where = method + " " + uri.getRawPath();
    me.didit.core.TransportRequest request = AuthRequests.create(method, uri, headers, body, timeout);
    CompletableFuture<TransportResponse> sent;
    try {
      sent = transport.send(request);
    } catch (RuntimeException e) {
      sent = CompletableFuture.failedFuture(e);
    }
    return sent.orTimeout(timeout.toMillis() + 50, TimeUnit.MILLISECONDS)
        .handle(
            (response, error) -> {
              if (error != null) {
                throw new CompletionException(connectionError(where, error));
              }
              int status = response.statusCode();
              if (status >= 200 && status < 300) {
                return response;
              }
              byte[] bytes;
              try (TransportResponse r = response) {
                bytes = r.body().readAllBytes();
              } catch (IOException e) {
                bytes = new byte[0];
              }
              JsonNode parsed = null;
              if (bytes.length > 0) {
                try {
                  parsed = Json.parse(bytes);
                } catch (UnexpectedResponseException e) {
                  parsed = Json.nodes().textNode(new String(bytes, StandardCharsets.UTF_8));
                }
              }
              throw new CompletionException(
                  ApiStatusException.create(status, oauthDetail(parsed), response.headers(), where));
            });
  }

  /** Puts {@code error: error_description} first so the exception message names the error. */
  private static @Nullable JsonNode oauthDetail(@Nullable JsonNode body) {
    if (body == null || !body.isObject() || !body.path("error").isTextual() || body.has("detail")) {
      return body;
    }
    ObjectNode copy = ((ObjectNode) body).deepCopy();
    String error = body.path("error").asText();
    String description = body.path("error_description").asText("");
    copy.put("detail", description.isEmpty() ? error : error + ": " + description);
    return copy;
  }

  private static DiditException connectionError(String where, Throwable error) {
    Throwable cause = error;
    while ((cause instanceof CompletionException || cause instanceof ExecutionException)
        && cause.getCause() != null) {
      cause = cause.getCause();
    }
    if (cause instanceof DiditException) {
      return (DiditException) cause;
    }
    if (cause instanceof TimeoutException || cause instanceof java.net.http.HttpTimeoutException) {
      return new ApiTimeoutException("Request timed out: " + where, cause);
    }
    return new ApiConnectionException("Connection error on " + where, cause);
  }

  private static ObjectNode jsonObject(TransportResponse response, String what) {
    byte[] bytes;
    try (TransportResponse r = response) {
      bytes = r.body().readAllBytes();
    } catch (IOException e) {
      throw new ApiConnectionException("Failed to read " + what, e);
    }
    JsonNode node = Json.parse(bytes);
    if (!node.isObject()) {
      throw new UnexpectedResponseException("The issuer returned " + what + " that is not a JSON object");
    }
    return (ObjectNode) node;
  }

  private static String userAgent() {
    return "didit-java/"
        + Version.VERSION
        + " (Java "
        + System.getProperty("java.version", "unknown")
        + "; "
        + System.getProperty("os.name", "unknown")
        + ")";
  }

  private static <T> T await(CompletableFuture<T> future) {
    try {
      return future.get();
    } catch (InterruptedException e) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new ApiConnectionException("Interrupted while waiting for the issuer", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      while ((cause instanceof CompletionException || cause instanceof ExecutionException)
          && cause.getCause() != null) {
        cause = cause.getCause();
      }
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      }
      throw new DiditException("Authentication call failed", cause);
    }
  }

  @Override
  public String toString() {
    return "Authentication{issuer=" + issuer + "}";
  }

  /** Builds an {@link Authentication}. */
  public static final class Builder {
    private @Nullable String issuer;
    private @Nullable String clientId;
    private Duration leeway = DEFAULT_LEEWAY;
    private Duration timeout = DEFAULT_TIMEOUT;
    private @Nullable HttpTransport transport;
    private @Nullable HttpClient httpClient;
    private Clock clock = Clock.systemUTC();

    private Builder() {}

    /**
     * The issuer: {@code https://<slug>.auth.didit.me} or your custom domain.
     *
     * @param issuer the issuer URL
     * @return this builder
     */
    public Builder issuer(String issuer) {
      this.issuer = Objects.requireNonNull(issuer, "issuer");
      return this;
    }

    /**
     * The issuer named by a publishable key.
     *
     * @param publishableKey {@code pk_live_...} or {@code pk_test_...}
     * @return this builder
     */
    public Builder publishableKey(String publishableKey) {
      this.issuer = PublishableKey.parse(publishableKey).issuer();
      return this;
    }

    /**
     * Your application id: sent with token requests and used as the default audience.
     *
     * @param clientId the application id
     * @return this builder
     */
    public Builder clientId(String clientId) {
      if (clientId.isEmpty()) {
        throw new ConfigurationException("clientId must not be empty");
      }
      this.clientId = clientId;
      return this;
    }

    /**
     * The clock tolerance for {@code exp}, {@code nbf} and {@code iat}. Defaults to 60 seconds.
     *
     * @param leeway zero or more
     * @return this builder
     */
    public Builder leeway(Duration leeway) {
      if (leeway.isNegative()) {
        throw new ConfigurationException("leeway must not be negative");
      }
      this.leeway = leeway;
      return this;
    }

    /**
     * The timeout of each call to the issuer. Defaults to 10 seconds. Calls are never retried.
     *
     * @param timeout a positive duration
     * @return this builder
     */
    public Builder timeout(Duration timeout) {
      if (timeout.isNegative() || timeout.isZero()) {
        throw new ConfigurationException("timeout must be positive");
      }
      this.timeout = timeout;
      return this;
    }

    /**
     * Sends requests with this {@link HttpClient}; it must not follow redirects.
     *
     * @param httpClient the client
     * @return this builder
     */
    public Builder httpClient(HttpClient httpClient) {
      this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
      return this;
    }

    /**
     * Sends requests through a custom transport, for example a stubbed issuer in tests.
     *
     * @param transport the transport
     * @return this builder
     */
    public Builder transport(HttpTransport transport) {
      this.transport = Objects.requireNonNull(transport, "transport");
      return this;
    }

    /**
     * The clock that gives "now", for tests.
     *
     * @param clock the clock
     * @return this builder
     */
    public Builder clock(Clock clock) {
      this.clock = Objects.requireNonNull(clock, "clock");
      return this;
    }

    /**
     * Builds the client.
     *
     * @return the client
     * @throws ConfigurationException when no valid issuer is set
     */
    public Authentication build() {
      if (issuer == null) {
        throw new ConfigurationException("Set the issuer or a publishable key");
      }
      return new Authentication(this, Issuers.normalize(issuer));
    }
  }

  static Instant epoch(double seconds) {
    return Instant.ofEpochMilli((long) (seconds * 1000));
  }
}
