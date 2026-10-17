package me.didit.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import me.didit.errors.UnexpectedResponseException;
import org.jspecify.annotations.Nullable;

/**
 * JSON helpers used by the generated types. Numbers are read losslessly: integers of any size and
 * decimals as {@link java.math.BigDecimal}.
 */
public final class Json {
  /** The mapper the SDK uses to read and write JSON. Do not reconfigure it. */
  static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .nodeFactory(JsonNodeFactory.withExactBigDecimals(true))
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
          .disable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
          .build();

  private static final Pattern SECRET_KEY =
      Pattern.compile("(?i).*(api_key|apikey|secret|password|token|authorization).*");

  private Json() {}

  /**
   * The shared JSON node factory, for building values to pass to the SDK.
   *
   * @return the node factory
   */
  public static JsonNodeFactory nodes() {
    return MAPPER.getNodeFactory();
  }

  /**
   * Parses JSON text.
   *
   * @param text JSON text
   * @return the parsed value
   * @throws UnexpectedResponseException when the text is not valid JSON
   */
  public static JsonNode parse(String text) {
    try {
      return MAPPER.readTree(text);
    } catch (JsonProcessingException e) {
      throw new UnexpectedResponseException("Invalid JSON: " + e.getOriginalMessage(), e);
    }
  }

  /**
   * Parses UTF-8 JSON bytes.
   *
   * @param bytes JSON bytes
   * @return the parsed value; {@link com.fasterxml.jackson.databind.node.MissingNode} when empty
   * @throws UnexpectedResponseException when the bytes are not valid JSON
   */
  public static JsonNode parse(byte[] bytes) {
    try {
      return MAPPER.readTree(bytes);
    } catch (IOException e) {
      throw new UnexpectedResponseException("Invalid JSON: " + e.getMessage(), e);
    }
  }

  /**
   * Converts a Java value (map, list, string, number, boolean, {@link JsonNode}, generated type)
   * to JSON.
   *
   * @param value the value; {@code null} becomes JSON null
   * @return the JSON value
   */
  public static JsonNode toNode(@Nullable Object value) {
    if (value == null) {
      return NullNode.getInstance();
    }
    if (value instanceof JsonNode) {
      return ((JsonNode) value).deepCopy();
    }
    if (value instanceof JsonModel) {
      return ((JsonModel) value).json.deepCopy();
    }
    if (value instanceof JsonUnion) {
      return ((JsonUnion) value).json.deepCopy();
    }
    if (value instanceof StringEnum) {
      return MAPPER.getNodeFactory().textNode(((StringEnum) value).value());
    }
    if (value instanceof java.time.temporal.TemporalAccessor) {
      return MAPPER.getNodeFactory().textNode(value.toString());
    }
    if (value instanceof Map) {
      ObjectNode out = MAPPER.createObjectNode();
      for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
        out.set(String.valueOf(entry.getKey()), toNode(entry.getValue()));
      }
      return out;
    }
    if (value instanceof Iterable) {
      com.fasterxml.jackson.databind.node.ArrayNode out = MAPPER.createArrayNode();
      for (Object item : (Iterable<?>) value) {
        out.add(toNode(item));
      }
      return out;
    }
    return MAPPER.valueToTree(value);
  }

  /**
   * Writes a JSON value as compact text.
   *
   * @param node the value
   * @return the JSON text
   */
  public static String write(JsonNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("JSON value cannot be written", e);
    }
  }

  static byte[] writeBytes(JsonNode node) {
    try {
      return MAPPER.writeValueAsBytes(node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("JSON value cannot be written", e);
    }
  }

  /**
   * Copies a JSON object.
   *
   * @param json a JSON value
   * @return a deep copy
   * @throws UnexpectedResponseException when the value is not an object
   */
  public static ObjectNode copyObject(JsonNode json) {
    if (!json.isObject()) {
      throw new UnexpectedResponseException("Expected a JSON object, got " + kind(json));
    }
    return ((ObjectNode) json).deepCopy();
  }

  /**
   * Reads a required field.
   *
   * @param <T> the Java type
   * @param json the object
   * @param field the JSON field name
   * @param conv the converter
   * @return the value
   * @throws UnexpectedResponseException when the field is absent, null or of another type
   */
  public static <T> T required(ObjectNode json, String field, Conv<T> conv) {
    JsonNode value = json.get(field);
    if (value == null || value.isNull() || value.isMissingNode()) {
      throw new UnexpectedResponseException(
          "Required field '" + field + "' is " + (value == null ? "missing" : "null"));
    }
    return read(field, value, conv);
  }

  /**
   * Reads an optional field.
   *
   * @param <T> the Java type
   * @param json the object
   * @param field the JSON field name
   * @param conv the converter
   * @return the value, or empty when absent or null
   * @throws UnexpectedResponseException when the field has another type
   */
  public static <T> Optional<T> optional(ObjectNode json, String field, Conv<T> conv) {
    JsonNode value = json.get(field);
    if (value == null || value.isNull() || value.isMissingNode()) {
      return Optional.empty();
    }
    return Optional.of(read(field, value, conv));
  }

  private static <T> T read(String field, JsonNode value, Conv<T> conv) {
    try {
      return conv.read(value);
    } catch (UnexpectedResponseException e) {
      throw new UnexpectedResponseException("Field '" + field + "': " + e.getMessage(), e);
    }
  }

  /**
   * Whether a field holds the given string, for union discrimination.
   *
   * @param json any JSON value
   * @param field the field name
   * @param value the expected string
   * @return true when {@code json} is an object whose field equals {@code value}
   */
  public static boolean hasValue(JsonNode json, String field, String value) {
    JsonNode actual = json.get(field);
    return json.isObject() && actual != null && actual.isTextual() && actual.asText().equals(value);
  }

  /**
   * Whether every field is present, for union discrimination.
   *
   * @param json any JSON value
   * @param fields the field names
   * @return true when {@code json} is an object holding all of them
   */
  public static boolean hasFields(JsonNode json, String... fields) {
    if (!json.isObject()) {
      return false;
    }
    for (String field : fields) {
      if (!json.has(field)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether any field is present, for union discrimination.
   *
   * @param json any JSON value
   * @param fields the field names
   * @return true when {@code json} is an object holding at least one of them
   */
  public static boolean hasAnyField(JsonNode json, String... fields) {
    if (!json.isObject()) {
      return false;
    }
    for (String field : fields) {
      if (json.has(field)) {
        return true;
      }
    }
    return false;
  }

  static String kind(JsonNode node) {
    return node.getNodeType().name().toLowerCase(java.util.Locale.ROOT);
  }

  /** Compact JSON with the values of secret-looking fields replaced, for {@code toString()}. */
  static String redacted(JsonNode node) {
    return write(redact(node));
  }

  private static JsonNode redact(JsonNode node) {
    if (node.isObject()) {
      ObjectNode out = MAPPER.createObjectNode();
      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> entry = fields.next();
        JsonNode value = entry.getValue();
        if (value.isTextual() && SECRET_KEY.matcher(entry.getKey()).matches()) {
          out.put(entry.getKey(), "[REDACTED]");
        } else {
          out.set(entry.getKey(), redact(value));
        }
      }
      return out;
    }
    if (node.isArray()) {
      com.fasterxml.jackson.databind.node.ArrayNode out = MAPPER.createArrayNode();
      for (JsonNode item : node) {
        out.add(redact(item));
      }
      return out;
    }
    return node;
  }
}
