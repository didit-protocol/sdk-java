package me.didit.webhooks;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The canonical JSON that {@code X-Signature-V2} signs (DESIGN 9.2): Python's {@code
 * json.dumps(shorten_floats(value), sort_keys=True, separators=(",", ":"), ensure_ascii=False)}.
 */
final class CanonicalJson {
  private CanonicalJson() {}

  /** Thrown for values Python could not have produced, such as NaN or lone surrogates. */
  static final class InvalidValueException extends Exception {
    private static final long serialVersionUID = 1L;

    InvalidValueException(String message) {
      super(message);
    }
  }

  static String write(JsonNode value) throws InvalidValueException {
    StringBuilder out = new StringBuilder();
    write(value, out, 0);
    return out.toString();
  }

  private static void write(JsonNode value, StringBuilder out, int depth)
      throws InvalidValueException {
    if (depth > 512) {
      throw new InvalidValueException("JSON nested too deeply");
    }
    if (value.isObject()) {
      List<Map.Entry<String, JsonNode>> entries = new ArrayList<>(value.properties());
      entries.sort((a, b) -> compareCodePoints(a.getKey(), b.getKey()));
      out.append('{');
      boolean first = true;
      for (Map.Entry<String, JsonNode> entry : entries) {
        if (!first) {
          out.append(',');
        }
        first = false;
        string(entry.getKey(), out);
        out.append(':');
        write(entry.getValue(), out, depth + 1);
      }
      out.append('}');
    } else if (value.isArray()) {
      out.append('[');
      boolean first = true;
      for (JsonNode item : value) {
        if (!first) {
          out.append(',');
        }
        first = false;
        write(item, out, depth + 1);
      }
      out.append(']');
    } else if (value.isTextual()) {
      string(value.textValue(), out);
    } else if (value.isNumber()) {
      number(value, out);
    } else if (value.isBoolean()) {
      out.append(value.booleanValue() ? "true" : "false");
    } else if (value.isNull()) {
      out.append("null");
    } else {
      throw new InvalidValueException("Unsupported JSON value: " + value.getNodeType());
    }
  }

  /** Orders strings by Unicode code point, like Python, not by UTF-16 code unit. */
  static int compareCodePoints(String a, String b) {
    int i = 0;
    int j = 0;
    while (i < a.length() && j < b.length()) {
      int ca = a.codePointAt(i);
      int cb = b.codePointAt(j);
      if (ca != cb) {
        return Integer.compare(ca, cb);
      }
      i += Character.charCount(ca);
      j += Character.charCount(cb);
    }
    return Integer.compare(a.length() - i, b.length() - j);
  }

  private static void string(String text, StringBuilder out) throws InvalidValueException {
    out.append('"');
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
          out.append(c).append(text.charAt(i + 1));
          i++;
          continue;
        }
        throw new InvalidValueException("Lone surrogate in a string");
      }
      if (Character.isLowSurrogate(c)) {
        throw new InvalidValueException("Lone surrogate in a string");
      }
      switch (c) {
        case '"':
          out.append("\\\"");
          break;
        case '\\':
          out.append("\\\\");
          break;
        case '\n':
          out.append("\\n");
          break;
        case '\r':
          out.append("\\r");
          break;
        case '\t':
          out.append("\\t");
          break;
        case '\b':
          out.append("\\b");
          break;
        case '\f':
          out.append("\\f");
          break;
        default:
          if (c < 0x20) {
            out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
          } else {
            out.append(c);
          }
      }
    }
    out.append('"');
  }

  /**
   * Python parses a number token without a fraction or exponent as an exact integer and anything
   * else as a double; {@code shorten_floats} then turns doubles with an integral value into
   * integers.
   */
  private static void number(JsonNode value, StringBuilder out) throws InvalidValueException {
    if (value.isIntegralNumber()) {
      out.append(value.bigIntegerValue().toString());
      return;
    }
    BigDecimal decimal = value.decimalValue();
    double d = Double.parseDouble(decimal.toString());
    if (Double.isNaN(d) || Double.isInfinite(d)) {
      throw new InvalidValueException("Number out of range for a double: " + decimal);
    }
    if (d == Math.rint(d)) {
      out.append(new BigDecimal(d).toBigIntegerExact().toString());
      return;
    }
    out.append(pythonRepr(d));
  }

  /**
   * Python's {@code repr(float)}: the shortest digits that read back as the same double, in
   * scientific notation when the decimal exponent is below -4 or at least 16.
   */
  static String pythonRepr(double d) {
    if (d == 0) {
      return (1 / d < 0) ? "-0.0" : "0.0";
    }
    BigDecimal shortest = shortest(d);
    String sign = shortest.signum() < 0 ? "-" : "";
    BigDecimal abs = shortest.abs();
    String digits = abs.unscaledValue().toString();
    // Strip trailing zeros from the digits while keeping the value.
    int trailing = 0;
    while (trailing < digits.length() - 1 && digits.charAt(digits.length() - 1 - trailing) == '0') {
      trailing++;
    }
    digits = digits.substring(0, digits.length() - trailing);
    int scale = abs.scale() - trailing;
    // value = 0.digits * 10^exponent10 where exponent10 = digits.length - scale
    int pointPosition = digits.length() - scale;
    int exponent = pointPosition - 1;
    if (exponent < -4 || exponent >= 16) {
      StringBuilder out = new StringBuilder(sign).append(digits.charAt(0));
      if (digits.length() > 1) {
        out.append('.').append(digits, 1, digits.length());
      }
      out.append('e').append(exponent < 0 ? '-' : '+');
      int e = Math.abs(exponent);
      if (e < 10) {
        out.append('0');
      }
      return out.append(e).toString();
    }
    StringBuilder out = new StringBuilder(sign);
    if (pointPosition <= 0) {
      out.append("0.");
      for (int i = 0; i < -pointPosition; i++) {
        out.append('0');
      }
      out.append(digits);
    } else if (pointPosition >= digits.length()) {
      out.append(digits);
      for (int i = digits.length(); i < pointPosition; i++) {
        out.append('0');
      }
      out.append(".0");
    } else {
      out.append(digits, 0, pointPosition)
          .append('.')
          .append(digits, pointPosition, digits.length());
    }
    return out.toString();
  }

  /**
   * The shortest decimal that reads back as {@code d}; among candidates of that length, the one
   * closest to the exact binary value, like David Gay's algorithm that Python uses.
   */
  static BigDecimal shortest(double d) {
    BigDecimal exact = new BigDecimal(d);
    for (int precision = 1; precision <= 17; precision++) {
      BigDecimal down = exact.round(new MathContext(precision, RoundingMode.DOWN));
      BigDecimal up = exact.round(new MathContext(precision, RoundingMode.UP));
      boolean downOk = Double.parseDouble(down.toString()) == d;
      boolean upOk = Double.parseDouble(up.toString()) == d;
      if (downOk && upOk) {
        int cmp = exact.subtract(down).abs().compareTo(up.subtract(exact).abs());
        if (cmp < 0) {
          return down;
        }
        if (cmp > 0) {
          return up;
        }
        BigInteger lastDown = down.unscaledValue();
        return lastDown.testBit(0) ? up : down;
      }
      if (downOk) {
        return down;
      }
      if (upOk) {
        return up;
      }
    }
    return exact.round(new MathContext(17, RoundingMode.HALF_EVEN));
  }
}
