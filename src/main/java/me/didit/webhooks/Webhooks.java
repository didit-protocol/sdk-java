package me.didit.webhooks;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import me.didit.core.Json;
import me.didit.errors.ConfigurationException;
import me.didit.errors.WebhookVerificationException;
import me.didit.errors.WebhookVerificationException.Reason;
import org.jspecify.annotations.Nullable;

/**
 * Verifies Didit webhook deliveries and parses them into typed events (DESIGN 9). No framework
 * dependency: pass the raw body and the request headers.
 *
 * <pre>{@code
 * WebhookEvent event = Webhooks.verify(rawBody, headers, System.getenv("DIDIT_WEBHOOK_SECRET"));
 * if (event instanceof SessionStatusUpdatedEvent) {
 *   SessionStatusUpdatedEvent e = (SessionStatusUpdatedEvent) event;
 *   System.out.println(e.sessionId() + " is " + e.status());
 * }
 * }</pre>
 *
 * <p>Verify the raw body whenever your framework gives access to it. {@code X-Signature-V2} also
 * survives middleware that re-encodes JSON, and {@link #verifyParsed} checks only that one.
 */
public final class Webhooks {
  /** The signature headers, current secret first. */
  static final String SIGNATURE = "X-Signature";

  static final String SIGNATURE_PREVIOUS = "X-Signature-Previous";
  static final String SIGNATURE_V2 = "X-Signature-V2";
  static final String SIGNATURE_V2_PREVIOUS = "X-Signature-V2-Previous";
  static final String SIGNATURE_SIMPLE = "X-Signature-Simple";
  static final String SIGNATURE_SIMPLE_PREVIOUS = "X-Signature-Simple-Previous";
  static final String TIMESTAMP = "X-Timestamp";

  private static final List<String> ALL_SIGNATURES =
      List.of(
          SIGNATURE,
          SIGNATURE_PREVIOUS,
          SIGNATURE_V2,
          SIGNATURE_V2_PREVIOUS,
          SIGNATURE_SIMPLE,
          SIGNATURE_SIMPLE_PREVIOUS);
  private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern DIGITS = Pattern.compile("[0-9]{1,20}");

  private static final ObjectMapper STRICT =
      JsonMapper.builder()
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false)
          .disable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  static {
    STRICT
        .getFactory()
        .setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(500).build());
  }

  private Webhooks() {}

  /**
   * Verifies a delivery with one secret and the default options.
   *
   * @param rawBody the request body exactly as received
   * @param headers the request headers; values may be strings or lists of strings
   * @param secret the endpoint's webhook secret
   * @return the typed event
   * @throws WebhookVerificationException when the delivery is not authentic or not fresh
   * @throws ConfigurationException when the secret is empty
   */
  public static WebhookEvent verify(String rawBody, Map<String, ?> headers, String secret) {
    return verify(
        rawBody.getBytes(StandardCharsets.UTF_8), lookup(headers), VerifyOptions.of(secret));
  }

  /**
   * Verifies a delivery with one secret and the default options.
   *
   * @param rawBody the request body exactly as received
   * @param headers the request headers; values may be strings or lists of strings
   * @param secret the endpoint's webhook secret
   * @return the typed event
   * @throws WebhookVerificationException when the delivery is not authentic or not fresh
   * @throws ConfigurationException when the secret is empty
   */
  public static WebhookEvent verify(byte[] rawBody, Map<String, ?> headers, String secret) {
    return verify(rawBody, lookup(headers), VerifyOptions.of(secret));
  }

  /**
   * Verifies a delivery.
   *
   * @param rawBody the request body exactly as received
   * @param headers the request headers; values may be strings or lists of strings
   * @param options the secrets, tolerance and Simple opt-in
   * @return the typed event
   * @throws WebhookVerificationException when the delivery is not authentic or not fresh
   * @throws ConfigurationException when no secret or an empty one is given
   */
  public static WebhookEvent verify(String rawBody, Map<String, ?> headers, VerifyOptions options) {
    return verify(rawBody.getBytes(StandardCharsets.UTF_8), lookup(headers), options);
  }

  /**
   * Verifies a delivery.
   *
   * @param rawBody the request body exactly as received
   * @param headers the request headers; values may be strings or lists of strings
   * @param options the secrets, tolerance and Simple opt-in
   * @return the typed event
   * @throws WebhookVerificationException when the delivery is not authentic or not fresh
   * @throws ConfigurationException when no secret or an empty one is given
   */
  public static WebhookEvent verify(byte[] rawBody, Map<String, ?> headers, VerifyOptions options) {
    return verify(rawBody, lookup(headers), options);
  }

  /**
   * Verifies a delivery with one secret, reading headers through a function such as {@code
   * request::getHeader} (Servlet) or {@code headers::getFirst} (Spring).
   *
   * @param rawBody the request body exactly as received
   * @param headers returns a header's value by name, or {@code null}
   * @param secret the endpoint's webhook secret
   * @return the typed event
   * @throws WebhookVerificationException when the delivery is not authentic or not fresh
   * @throws ConfigurationException when the secret is empty
   */
  public static WebhookEvent verify(
      byte[] rawBody, Function<String, @Nullable String> headers, String secret) {
    return verify(rawBody, headers, VerifyOptions.of(secret));
  }

  /**
   * Verifies a delivery, reading headers through a function such as {@code request::getHeader}.
   *
   * @param rawBody the request body exactly as received
   * @param headers returns a header's value by name, or {@code null}
   * @param options the secrets, tolerance and Simple opt-in
   * @return the typed event
   * @throws WebhookVerificationException when the delivery is not authentic or not fresh
   * @throws ConfigurationException when no secret or an empty one is given
   */
  public static WebhookEvent verify(
      byte[] rawBody, Function<String, @Nullable String> headers, VerifyOptions options) {
    List<String> secrets = secrets(options);
    ObjectNode payload = parse(rawBody);
    checkTimestamp(payload, headers, options);
    requireAnySignature(headers);
    for (String secret : secrets) {
      String expected = hmac(secret, rawBody);
      if (matches(expected, headers, SIGNATURE) || matches(expected, headers, SIGNATURE_PREVIOUS)) {
        return WebhookEvent.of(payload, VerifiedBy.SIGNATURE);
      }
    }
    return verifyCanonicalOrSimple(payload, headers, secrets, options.allowSimple());
  }

  /**
   * Verifies a delivery whose body the framework already parsed. Only {@code X-Signature-V2} can be
   * checked this way; prefer {@link #verify} with the raw body.
   *
   * @param payload the parsed JSON body
   * @param headers the request headers; values may be strings or lists of strings
   * @param secret the endpoint's webhook secret
   * @return the typed event
   * @throws WebhookVerificationException when the delivery is not authentic or not fresh
   * @throws ConfigurationException when the secret is empty
   */
  public static WebhookEvent verifyParsed(JsonNode payload, Map<String, ?> headers, String secret) {
    return verifyParsed(payload, lookup(headers), VerifyOptions.of(secret));
  }

  /**
   * Verifies a delivery whose body the framework parsed into a map (for example Spring's
   * {@code @RequestBody Map<String, Object>}). Only {@code X-Signature-V2} can be checked this way.
   *
   * @param payload the parsed JSON body
   * @param headers the request headers; values may be strings or lists of strings
   * @param secret the endpoint's webhook secret
   * @return the typed event
   * @throws WebhookVerificationException when the delivery is not authentic or not fresh
   * @throws ConfigurationException when the secret is empty
   */
  public static WebhookEvent verifyParsed(
      Map<String, ?> payload, Map<String, ?> headers, String secret) {
    return verifyParsed(Json.toNode(payload), lookup(headers), VerifyOptions.of(secret));
  }

  /**
   * Verifies a delivery whose body the framework already parsed. Only {@code X-Signature-V2} can be
   * checked this way; {@link VerifyOptions.Builder#allowSimple} applies to {@link #verify} only.
   *
   * @param payload the parsed JSON body
   * @param headers the request headers; values may be strings or lists of strings
   * @param options the secrets and tolerance
   * @return the typed event
   * @throws WebhookVerificationException when the delivery is not authentic or not fresh
   * @throws ConfigurationException when no secret or an empty one is given
   */
  public static WebhookEvent verifyParsed(
      JsonNode payload, Map<String, ?> headers, VerifyOptions options) {
    return verifyParsed(payload, lookup(headers), options);
  }

  /**
   * Verifies a delivery whose body the framework already parsed, reading headers through a
   * function. Only {@code X-Signature-V2} can be checked this way; {@link
   * VerifyOptions.Builder#allowSimple} applies to {@link #verify} only.
   *
   * @param payload the parsed JSON body
   * @param headers returns a header's value by name, or {@code null}
   * @param options the secrets and tolerance
   * @return the typed event
   * @throws WebhookVerificationException when the delivery is not authentic or not fresh
   * @throws ConfigurationException when no secret or an empty one is given
   */
  public static WebhookEvent verifyParsed(
      JsonNode payload, Function<String, @Nullable String> headers, VerifyOptions options) {
    List<String> secrets = secrets(options);
    if (!payload.isObject()) {
      throw new WebhookVerificationException(
          Reason.INVALID_PAYLOAD, "The body is not a JSON object");
    }
    ObjectNode object = ((ObjectNode) payload).deepCopy();
    checkTimestamp(object, headers, options);
    requireAnySignature(headers);
    return verifyCanonicalOrSimple(object, headers, secrets, false);
  }

  /**
   * The canonical JSON of a body: what {@code X-Signature-V2} signs (DESIGN 9.2).
   *
   * @param rawBody the request body
   * @return the canonical JSON
   * @throws WebhookVerificationException with {@code invalid_payload} when the body is not JSON
   *     Didit could have sent
   */
  public static String canonicalJson(String rawBody) {
    return canonical(parse(rawBody.getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * The canonical JSON of a parsed body.
   *
   * @param payload the parsed body
   * @return the canonical JSON
   * @throws WebhookVerificationException with {@code invalid_payload} for NaN, infinite numbers or
   *     lone surrogates
   */
  public static String canonicalJson(JsonNode payload) {
    return canonical(payload);
  }

  /**
   * Parses a payload into a typed event without verifying it, for payloads that were verified
   * elsewhere or for tests. {@link WebhookEvent#verifiedBy()} is {@link VerifiedBy#NONE}.
   *
   * @param payload the JSON body
   * @return the typed event
   * @throws WebhookVerificationException with {@code invalid_payload} when it is not an object
   */
  public static WebhookEvent parseEvent(JsonNode payload) {
    if (!payload.isObject()) {
      throw new WebhookVerificationException(
          Reason.INVALID_PAYLOAD, "The body is not a JSON object");
    }
    return WebhookEvent.of(((ObjectNode) payload).deepCopy(), VerifiedBy.NONE);
  }

  // -- steps -------------------------------------------------------------------

  private static WebhookEvent verifyCanonicalOrSimple(
      ObjectNode payload,
      Function<String, @Nullable String> headers,
      List<String> secrets,
      boolean allowSimple) {
    boolean anyV2 = present(headers, SIGNATURE_V2) || present(headers, SIGNATURE_V2_PREVIOUS);
    if (anyV2) {
      byte[] canonical = canonical(payload).getBytes(StandardCharsets.UTF_8);
      for (String secret : secrets) {
        String expected = hmac(secret, canonical);
        if (matches(expected, headers, SIGNATURE_V2)
            || matches(expected, headers, SIGNATURE_V2_PREVIOUS)) {
          return WebhookEvent.of(payload, VerifiedBy.SIGNATURE_V2);
        }
      }
    }
    if (allowSimple) {
      byte[] simple = simpleString(payload).getBytes(StandardCharsets.UTF_8);
      for (String secret : secrets) {
        String expected = hmac(secret, simple);
        if (matches(expected, headers, SIGNATURE_SIMPLE)
            || matches(expected, headers, SIGNATURE_SIMPLE_PREVIOUS)) {
          return WebhookEvent.of(payload, VerifiedBy.SIGNATURE_SIMPLE);
        }
      }
    }
    throw new WebhookVerificationException(
        Reason.INVALID_SIGNATURE, "No webhook signature matched the secret");
  }

  private static List<String> secrets(VerifyOptions options) {
    List<String> secrets = options.secrets();
    if (secrets.isEmpty()) {
      throw new ConfigurationException("A webhook secret is required");
    }
    for (String secret : secrets) {
      if (secret.trim().isEmpty()) {
        throw new ConfigurationException("A webhook secret must not be empty");
      }
    }
    return secrets;
  }

  static ObjectNode parse(byte[] rawBody) {
    try {
      StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(rawBody));
    } catch (CharacterCodingException e) {
      throw new WebhookVerificationException(Reason.INVALID_PAYLOAD, "The body is not UTF-8");
    }
    JsonNode node;
    try (JsonParser parser = STRICT.createParser(rawBody)) {
      node = STRICT.readTree(parser);
      if (parser.nextToken() != null) {
        throw new WebhookVerificationException(Reason.INVALID_PAYLOAD, "Trailing data after JSON");
      }
    } catch (IOException e) {
      throw new WebhookVerificationException(Reason.INVALID_PAYLOAD, "The body is not valid JSON");
    }
    if (node == null || !node.isObject()) {
      throw new WebhookVerificationException(
          Reason.INVALID_PAYLOAD, "The body is not a JSON object");
    }
    return (ObjectNode) node;
  }

  private static void checkTimestamp(
      ObjectNode payload, Function<String, @Nullable String> headers, VerifyOptions options) {
    JsonNode value = payload.get("timestamp");
    if (value == null || !value.isIntegralNumber()) {
      throw new WebhookVerificationException(
          Reason.MISSING_TIMESTAMP, "The body has no integer timestamp");
    }
    BigInteger timestamp = value.bigIntegerValue();
    String header = header(headers, TIMESTAMP);
    if (header != null) {
      if (!DIGITS.matcher(header).matches() || !new BigInteger(header).equals(timestamp)) {
        throw new WebhookVerificationException(
            Reason.TIMESTAMP_MISMATCH, "X-Timestamp differs from the body's timestamp");
      }
    }
    BigInteger now = BigInteger.valueOf(options.clock().instant().getEpochSecond());
    BigInteger tolerance = BigInteger.valueOf(options.tolerance().toSeconds());
    if (now.subtract(timestamp).abs().compareTo(tolerance) > 0) {
      throw new WebhookVerificationException(
          Reason.TIMESTAMP_OUT_OF_TOLERANCE,
          "The delivery timestamp is more than " + tolerance + " seconds from now");
    }
  }

  private static void requireAnySignature(Function<String, @Nullable String> headers) {
    for (String name : ALL_SIGNATURES) {
      if (present(headers, name)) {
        return;
      }
    }
    throw new WebhookVerificationException(
        Reason.MISSING_SIGNATURE, "The delivery has no signature header");
  }

  private static String canonical(JsonNode payload) {
    try {
      return CanonicalJson.write(payload);
    } catch (CanonicalJson.InvalidValueException e) {
      throw new WebhookVerificationException(
          Reason.INVALID_PAYLOAD, String.valueOf(e.getMessage()));
    }
  }

  /** {@code "{timestamp}:{session_id}:{status}:{webhook_type}"} with Python's {@code str()}. */
  static String simpleString(ObjectNode payload) {
    return pythonStr(payload.get("timestamp"))
        + ":"
        + pythonStr(payload.get("session_id"))
        + ":"
        + pythonStr(payload.get("status"))
        + ":"
        + pythonStr(payload.get("webhook_type"));
  }

  static String pythonStr(@Nullable JsonNode value) {
    if (value == null) {
      return "";
    }
    if (value.isTextual()) {
      return value.textValue();
    }
    return pythonRepr(value);
  }

  private static String pythonRepr(JsonNode value) {
    if (value.isNull()) {
      return "None";
    }
    if (value.isBoolean()) {
      return value.booleanValue() ? "True" : "False";
    }
    if (value.isIntegralNumber()) {
      return value.bigIntegerValue().toString();
    }
    if (value.isNumber()) {
      BigDecimal decimal = value.decimalValue();
      return CanonicalJson.pythonRepr(Double.parseDouble(decimal.toString()));
    }
    if (value.isTextual()) {
      String text = value.textValue();
      String quote = text.contains("'") && !text.contains("\"") ? "\"" : "'";
      return quote + text.replace("\\", "\\\\").replace(quote, "\\" + quote) + quote;
    }
    StringBuilder out = new StringBuilder();
    if (value.isArray()) {
      out.append('[');
      boolean first = true;
      for (JsonNode item : value) {
        out.append(first ? "" : ", ").append(pythonRepr(item));
        first = false;
      }
      return out.append(']').toString();
    }
    out.append('{');
    boolean first = true;
    for (Map.Entry<String, JsonNode> entry : value.properties()) {
      out.append(first ? "" : ", ");
      out.append(pythonRepr(Json.nodes().textNode(entry.getKey())));
      out.append(": ").append(pythonRepr(entry.getValue()));
      first = false;
    }
    return out.append('}').toString();
  }

  static String hmac(String secret, byte[] message) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal(message);
      StringBuilder hex = new StringBuilder(64);
      for (byte b : digest) {
        hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HmacSHA256 is not available", e);
    }
  }

  private static boolean matches(
      String expected, Function<String, @Nullable String> headers, String name) {
    String provided = header(headers, name);
    if (provided == null || !HEX64.matcher(provided).matches()) {
      return false;
    }
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.US_ASCII), provided.getBytes(StandardCharsets.US_ASCII));
  }

  private static boolean present(Function<String, @Nullable String> headers, String name) {
    return header(headers, name) != null;
  }

  /** A trimmed header value; empty values count as absent. */
  private static @Nullable String header(Function<String, @Nullable String> headers, String name) {
    String value = headers.apply(name);
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /**
   * Header lookup over a map: names compare without case, the first matching entry wins, and a list
   * value gives its first item.
   */
  static Function<String, @Nullable String> lookup(Map<String, ?> headers) {
    Objects.requireNonNull(headers, "headers");
    return name -> {
      for (Map.Entry<String, ?> entry : headers.entrySet()) {
        if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
          return first(entry.getValue());
        }
      }
      return null;
    };
  }

  private static @Nullable String first(@Nullable Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof String) {
      return (String) value;
    }
    if (value instanceof Collection) {
      for (Object item : (Collection<?>) value) {
        return item == null ? null : item.toString();
      }
      return null;
    }
    if (value instanceof String[]) {
      String[] array = (String[]) value;
      return array.length == 0 ? null : array[0];
    }
    return value.toString();
  }
}
