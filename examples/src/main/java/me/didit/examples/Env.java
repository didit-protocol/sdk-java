package me.didit.examples;

import me.didit.Didit;

/** Reads the example configuration from the environment. */
final class Env {
  private Env() {}

  /** A client from DIDIT_API_KEY, pointed at DIDIT_BASE_URL when set (for a local mock). */
  static Didit client() {
    Didit.Builder builder = Didit.builder();
    String baseUrl = System.getenv("DIDIT_BASE_URL");
    if (baseUrl != null && !baseUrl.isEmpty()) {
      builder.baseUrl(baseUrl);
    }
    return builder.build();
  }

  static String require(String name) {
    String value = System.getenv(name);
    if (value == null || value.isEmpty()) {
      throw new IllegalStateException("Set " + name);
    }
    return value;
  }
}
