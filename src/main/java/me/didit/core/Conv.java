package me.didit.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import me.didit.errors.UnexpectedResponseException;

/**
 * Converts between JSON values and the Java types of generated fields. Reading is lenient where
 * the meaning is unambiguous (a number sent as a string), and strict otherwise.
 *
 * @param <T> the Java type
 */
public abstract class Conv<T> {
  private static final JsonNodeFactory NODES = Json.MAPPER.getNodeFactory();
  private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
  private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

  /** Strings. Numbers and booleans are read as their text. */
  public static final Conv<String> STRING =
      new Conv<>() {
        @Override
        public String read(JsonNode node) {
          if (node.isTextual() || node.isNumber() || node.isBoolean()) {
            return node.asText();
          }
          throw mismatch("a string", node);
        }

        @Override
        public JsonNode write(String value) {
          return NODES.textNode(value);
        }
      };

  /** Integers that fit in a {@code long}. */
  public static final Conv<Long> LONG =
      new Conv<>() {
        @Override
        public Long read(JsonNode node) {
          BigDecimal decimal = decimal(node, "an integer");
          try {
            BigInteger integer = decimal.toBigIntegerExact();
            if (integer.compareTo(LONG_MIN) < 0 || integer.compareTo(LONG_MAX) > 0) {
              throw mismatch("an integer that fits in 64 bits", node);
            }
            return integer.longValue();
          } catch (ArithmeticException e) {
            throw mismatch("an integer", node);
          }
        }

        @Override
        public JsonNode write(Long value) {
          return NODES.numberNode(value);
        }
      };

  /** Numbers. */
  public static final Conv<Double> DOUBLE =
      new Conv<>() {
        @Override
        public Double read(JsonNode node) {
          return decimal(node, "a number").doubleValue();
        }

        @Override
        public JsonNode write(Double value) {
          if (value.isNaN() || value.isInfinite()) {
            throw new IllegalArgumentException("JSON has no NaN or Infinity: " + value);
          }
          return NODES.numberNode(value);
        }
      };

  /** Booleans; the strings {@code "true"} and {@code "false"} are accepted too. */
  public static final Conv<Boolean> BOOLEAN =
      new Conv<>() {
        @Override
        public Boolean read(JsonNode node) {
          if (node.isBoolean()) {
            return node.booleanValue();
          }
          if (node.isTextual() && ("true".equals(node.asText()) || "false".equals(node.asText()))) {
            return Boolean.parseBoolean(node.asText());
          }
          throw mismatch("a boolean", node);
        }

        @Override
        public JsonNode write(Boolean value) {
          return NODES.booleanNode(value);
        }
      };

  /**
   * ISO 8601 date-times. A value without an offset is read as UTC, a space may separate the date
   * and the time, and a number is read as unix seconds.
   */
  public static final Conv<OffsetDateTime> DATE_TIME =
      new Conv<>() {
        @Override
        public OffsetDateTime read(JsonNode node) {
          if (node.isNumber()) {
            return OffsetDateTime.ofInstant(
                Instant.ofEpochSecond(node.asLong()), ZoneOffset.UTC);
          }
          if (!node.isTextual()) {
            throw mismatch("a date-time string", node);
          }
          String text = node.asText().trim();
          if (text.length() > 10 && text.charAt(10) == ' ') {
            text = text.substring(0, 10) + 'T' + text.substring(11); // Python str(datetime)
          }
          try {
            return OffsetDateTime.parse(text);
          } catch (DateTimeParseException e) {
            try {
              return LocalDateTime.parse(text).atOffset(ZoneOffset.UTC);
            } catch (DateTimeParseException e2) {
              try {
                return LocalDate.parse(text).atStartOfDay().atOffset(ZoneOffset.UTC);
              } catch (DateTimeParseException e3) {
                throw new UnexpectedResponseException("expected a date-time, got '" + text + "'");
              }
            }
          }
        }

        @Override
        public JsonNode write(OffsetDateTime value) {
          return NODES.textNode(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value));
        }
      };

  /** ISO 8601 dates ({@code 2026-11-01}); a date-time is read as its date. */
  public static final Conv<LocalDate> DATE =
      new Conv<>() {
        @Override
        public LocalDate read(JsonNode node) {
          if (!node.isTextual()) {
            throw mismatch("a date string", node);
          }
          String text = node.asText();
          try {
            return LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text);
          } catch (DateTimeParseException e) {
            throw new UnexpectedResponseException("expected a date, got '" + text + "'");
          }
        }

        @Override
        public JsonNode write(LocalDate value) {
          return NODES.textNode(DateTimeFormatter.ISO_LOCAL_DATE.format(value));
        }
      };

  /** Any JSON value, as is. */
  public static final Conv<JsonNode> ANY =
      new Conv<>() {
        @Override
        public JsonNode read(JsonNode node) {
          return node.deepCopy();
        }

        @Override
        public JsonNode write(JsonNode value) {
          return value.deepCopy();
        }
      };

  /** A free-form JSON object. */
  public static final Conv<Map<String, JsonNode>> OBJECT =
      new Conv<>() {
        @Override
        public Map<String, JsonNode> read(JsonNode node) {
          if (!node.isObject()) {
            throw mismatch("an object", node);
          }
          Map<String, JsonNode> out = new LinkedHashMap<>();
          for (Map.Entry<String, JsonNode> entry : node.properties()) {
            out.put(entry.getKey(), entry.getValue().deepCopy());
          }
          return Collections.unmodifiableMap(out);
        }

        @Override
        public JsonNode write(Map<String, JsonNode> value) {
          return Json.toNode(value);
        }
      };

  /**
   * Reads a JSON value.
   *
   * @param node a JSON value that is not null
   * @return the Java value
   * @throws UnexpectedResponseException when the value has another type
   */
  public abstract T read(JsonNode node);

  /**
   * Writes a Java value.
   *
   * @param value the Java value
   * @return the JSON value
   */
  public abstract JsonNode write(T value);

  /**
   * Lists of {@code item}.
   *
   * @param <T> the item type
   * @param item the item converter
   * @return the list converter
   */
  public static <T> Conv<List<T>> list(Conv<T> item) {
    return new Conv<>() {
      @Override
      public List<T> read(JsonNode node) {
        if (!node.isArray()) {
          throw mismatch("an array", node);
        }
        List<T> out = new ArrayList<>(node.size());
        int index = 0;
        for (JsonNode element : node) {
          if (element.isNull()) {
            throw new UnexpectedResponseException("item " + index + " is null");
          }
          try {
            out.add(item.read(element));
          } catch (UnexpectedResponseException e) {
            throw new UnexpectedResponseException("item " + index + ": " + e.getMessage(), e);
          }
          index++;
        }
        return Collections.unmodifiableList(out);
      }

      @Override
      public JsonNode write(List<T> value) {
        ArrayNode out = NODES.arrayNode();
        for (T element : value) {
          out.add(item.write(element));
        }
        return out;
      }
    };
  }

  /**
   * Objects whose values are {@code item}.
   *
   * @param <T> the value type
   * @param item the value converter
   * @return the map converter
   */
  public static <T> Conv<Map<String, T>> map(Conv<T> item) {
    return new Conv<>() {
      @Override
      public Map<String, T> read(JsonNode node) {
        if (!node.isObject()) {
          throw mismatch("an object", node);
        }
        Map<String, T> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
          if (entry.getValue().isNull()) {
            continue;
          }
          try {
            out.put(entry.getKey(), item.read(entry.getValue()));
          } catch (UnexpectedResponseException e) {
            throw new UnexpectedResponseException(
                "key '" + entry.getKey() + "': " + e.getMessage(), e);
          }
        }
        return Collections.unmodifiableMap(out);
      }

      @Override
      public JsonNode write(Map<String, T> value) {
        ObjectNode out = NODES.objectNode();
        for (Map.Entry<String, T> entry : value.entrySet()) {
          out.set(entry.getKey(), item.write(entry.getValue()));
        }
        return out;
      }
    };
  }

  /**
   * A generated object type.
   *
   * @param <T> the generated type
   * @param fromJson its {@code fromJson} method
   * @return the converter
   */
  public static <T extends JsonModel> Conv<T> model(Function<JsonNode, T> fromJson) {
    return new Conv<>() {
      @Override
      public T read(JsonNode node) {
        if (!node.isObject()) {
          throw mismatch("an object", node);
        }
        return fromJson.apply(node);
      }

      @Override
      public JsonNode write(T value) {
        return value.json.deepCopy();
      }
    };
  }

  /**
   * A generated union type.
   *
   * @param <T> the generated type
   * @param fromJson its {@code fromJson} method
   * @return the converter
   */
  public static <T extends JsonUnion> Conv<T> union(Function<JsonNode, T> fromJson) {
    return new Conv<>() {
      @Override
      public T read(JsonNode node) {
        return fromJson.apply(node);
      }

      @Override
      public JsonNode write(T value) {
        return value.json.deepCopy();
      }
    };
  }

  /**
   * A generated open enum.
   *
   * @param <T> the generated type
   * @param of its {@code of} method
   * @return the converter
   */
  public static <T extends StringEnum> Conv<T> enumeration(Function<String, T> of) {
    return new Conv<>() {
      @Override
      public T read(JsonNode node) {
        if (!node.isTextual()) {
          throw mismatch("a string", node);
        }
        return of.apply(node.asText());
      }

      @Override
      public JsonNode write(T value) {
        return NODES.textNode(value.value());
      }
    };
  }

  static BigDecimal decimal(JsonNode node, String expected) {
    if (node.isNumber()) {
      return node.decimalValue();
    }
    if (node.isTextual()) {
      try {
        return new BigDecimal(node.asText().trim());
      } catch (NumberFormatException e) {
        throw mismatch(expected, node);
      }
    }
    throw mismatch(expected, node);
  }

  static UnexpectedResponseException mismatch(String expected, JsonNode node) {
    return new UnexpectedResponseException("expected " + expected + ", got " + Json.kind(node));
  }
}
