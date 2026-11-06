package me.didit.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import me.didit.errors.WebhookVerificationException;
import me.didit.testing.TestJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class CanonicalJsonTest {
  @Test
  void floatsMatchPythonRepr() throws IOException {
    List<String[]> rows = new ArrayList<>();
    try (BufferedReader in =
        new BufferedReader(
            new InputStreamReader(
                Objects.requireNonNull(getClass().getResourceAsStream("/python-float-repr.txt")),
                StandardCharsets.UTF_8))) {
      for (String line = in.readLine(); line != null; line = in.readLine()) {
        if (!line.startsWith("#") && !line.isBlank()) {
          rows.add(line.split(" ", 2));
        }
      }
    }
    assertTrue(rows.size() > 100);
    for (String[] row : rows) {
      double d = Double.parseDouble(row[0]);
      assertEquals(row[1], CanonicalJson.pythonRepr(d), row[0]);
      // Through the parser too: the repr reads back to the same canonical text.
      assertEquals("{\"v\":" + row[1] + "}", Webhooks.canonicalJson("{\"v\":" + row[1] + "}"));
    }
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "1e16|10000000000000000",
        "-0.0|0",
        "-0|0",
        "87.0|87",
        "1E2|100",
        "2.50|2.5",
        "12345678901234567890123|12345678901234567890123",
        "1e23|99999999999999991611392",
        "0.30000000000000004|0.30000000000000004",
        "1.5e-5|1.5e-05",
        "[1,2.0,3.25]|[1,2,3.25]",
        "true|true",
        "null|null"
      })
  void numbersFollowShortenFloats(String input, String expected) {
    assertEquals("{\"v\":" + expected + "}", Webhooks.canonicalJson("{\"v\":" + input + "}"));
  }

  @Test
  void hugeIntegralFloatIsWrittenAsItsExactInteger() {
    String canonical = Webhooks.canonicalJson("{\"v\":1e300}");
    assertTrue(canonical.startsWith("{\"v\":10000000000000000525047602552044202487"));
    assertEquals(301 + 6, canonical.length());
  }

  @Test
  void keysAreSortedByCodePoint() {
    // U+1F600 (astral) sorts after U+FFFD by code point, before it by UTF-16 unit.
    String json = "{\"\\ufffd\":1,\"\\ud83d\\ude00\":2,\"b\":3,\"a\":{\"z\":1,\"y\":2}}";
    assertEquals(
        "{\"a\":{\"y\":2,\"z\":1},\"b\":3,\"\ufffd\":1,\"\ud83d\ude00\":2}",
        Webhooks.canonicalJson(json));
  }

  @Test
  void stringsEscapeLikePython() {
    String json =
        "{\"s\":\"q\\\" b\\\\ n\\n r\\r t\\t b\\b f\\f c\\u0001 d\\u007f / \\u2028 \\u00e9\"}";
    assertEquals(
        "{\"s\":\"q\\\" b\\\\ n\\n r\\r t\\t b\\b f\\f c\\u0001 d\u007f / \u2028 \u00e9\"}",
        Webhooks.canonicalJson(json));
  }

  @Test
  void duplicateKeysKeepTheLastValue() {
    assertEquals("{\"a\":2}", Webhooks.canonicalJson("{\"a\":1,\"a\":2}"));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "{\"a\":NaN}",
        "{\"a\":Infinity}",
        "{\"a\":1e400}",
        "{\"a\":\"\\ud800\"}",
        "[1]",
        "nope",
        "{\"a\":1} x"
      })
  void rejectsWhatPythonCannotHaveSigned(String input) {
    WebhookVerificationException e =
        assertThrows(WebhookVerificationException.class, () -> Webhooks.canonicalJson(input));
    assertEquals("invalid_payload", e.reason().code());
  }

  @Test
  void canonicalOfParsedValue() {
    assertEquals(
        "{\"a\":[1,0.5]}", Webhooks.canonicalJson(TestJson.parse("{ \"a\" : [ 1.0 , 0.5 ] }")));
  }

  @Test
  void codePointOrder() {
    assertTrue(CanonicalJson.compareCodePoints("a", "ab") < 0);
    assertTrue(CanonicalJson.compareCodePoints("b", "a") > 0);
    assertEquals(0, CanonicalJson.compareCodePoints("abc", "abc"));
  }
}
