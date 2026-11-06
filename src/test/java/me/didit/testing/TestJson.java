package me.didit.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.Comparator;

/** JSON helpers for tests. Numbers compare by value (1 equals 1.0 and 1L). */
public final class TestJson {
  private static final ObjectMapper MAPPER =
      JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

  private static final Comparator<JsonNode> NUMBERS =
      (a, b) -> {
        if (a.isNumber() && b.isNumber()) {
          BigDecimal x = a.decimalValue();
          BigDecimal y = b.decimalValue();
          return x.compareTo(y);
        }
        return a.equals(b) ? 0 : 1;
      };

  private TestJson() {}

  public static JsonNode parse(String text) {
    try {
      return MAPPER.readTree(text);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static JsonNode parse(byte[] bytes) {
    try {
      return MAPPER.readTree(bytes);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static boolean jsonEquals(JsonNode expected, JsonNode actual) {
    return expected.equals(NUMBERS, actual);
  }

  public static void assertJsonEquals(JsonNode expected, JsonNode actual) {
    if (!jsonEquals(expected, actual)) {
      assertEquals(expected.toPrettyString(), actual.toPrettyString());
      throw new AssertionError("JSON differs only in number types");
    }
  }

  public static void assertJsonEquals(String expected, byte[] actual) {
    assertJsonEquals(parse(expected), parse(actual));
  }

  public static void assertJsonEquals(String expected, JsonNode actual) {
    assertJsonEquals(parse(expected), actual);
  }
}
