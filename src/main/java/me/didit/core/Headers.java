package me.didit.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Immutable HTTP headers with case-insensitive names. */
public final class Headers {
  /** The headers that carry the API's request id, in the order they are read. */
  public static final List<String> REQUEST_ID_HEADERS =
      List.of("X-Request-Id", "Request-Id", "X-Amzn-Requestid", "X-Correlation-Id");

  private static final Headers EMPTY = new Headers(new TreeMap<>(String.CASE_INSENSITIVE_ORDER));

  private final Map<String, List<String>> values;

  private Headers(Map<String, List<String>> values) {
    this.values = Collections.unmodifiableMap(values);
  }

  /**
   * Returns headers without any value.
   *
   * @return empty headers
   */
  public static Headers empty() {
    return EMPTY;
  }

  /**
   * Copies a header map. Names are compared without case; values keep their order.
   *
   * @param map header names to values
   * @return the headers
   */
  public static Headers of(Map<String, ? extends List<String>> map) {
    TreeMap<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (Map.Entry<String, ? extends List<String>> entry : map.entrySet()) {
      if (entry.getKey() == null) {
        continue; // java.net.http reports the status line under a null name
      }
      copy.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(entry.getValue());
    }
    copy.replaceAll((k, v) -> List.copyOf(v));
    return new Headers(copy);
  }

  /**
   * The first value of a header.
   *
   * @param name the header name, any case
   * @return the value, if present
   */
  public Optional<String> firstValue(String name) {
    List<String> list = values.get(name);
    return list == null || list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  /**
   * Every value of a header.
   *
   * @param name the header name, any case
   * @return the values, possibly empty
   */
  public List<String> allValues(String name) {
    List<String> list = values.get(name);
    return list == null ? List.of() : list;
  }

  /**
   * The request id the API assigned to this response.
   *
   * @return the first of {@link #REQUEST_ID_HEADERS} that is present
   */
  public Optional<String> requestId() {
    for (String name : REQUEST_ID_HEADERS) {
      Optional<String> value = firstValue(name).filter(v -> !v.isEmpty());
      if (value.isPresent()) {
        return value;
      }
    }
    return Optional.empty();
  }

  /**
   * Every header as an unmodifiable map with case-insensitive keys.
   *
   * @return the headers
   */
  public Map<String, List<String>> map() {
    return values;
  }

  /** Header names that are never printed by {@link #toString()}. */
  static boolean isSensitive(String name) {
    String lower = name.toLowerCase(Locale.ROOT);
    return lower.equals("x-api-key")
        || lower.equals("authorization")
        || lower.equals("proxy-authorization")
        || lower.equals("cookie")
        || lower.equals("set-cookie")
        || lower.startsWith("x-signature");
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Headers && values.equals(((Headers) other).values);
  }

  @Override
  public int hashCode() {
    return values.hashCode();
  }

  @Override
  public String toString() {
    StringBuilder out = new StringBuilder("Headers{");
    boolean first = true;
    for (Map.Entry<String, List<String>> entry : values.entrySet()) {
      if (!first) {
        out.append(", ");
      }
      first = false;
      out.append(entry.getKey()).append('=');
      out.append(isSensitive(entry.getKey()) ? "[REDACTED]" : String.join(",", entry.getValue()));
    }
    return out.append('}').toString();
  }
}
