package me.didit.core;

/** Describes the runtime for the {@code User-Agent} header. */
final class Platform {
  private static final String USER_AGENT = build();

  private Platform() {}

  static String userAgent() {
    return USER_AGENT;
  }

  private static String build() {
    String java = System.getProperty("java.version", "unknown");
    String os = System.getProperty("os.name", "unknown");
    return "didit-java/" + Version.VERSION + " (Java " + clean(java) + "; " + clean(os) + ")";
  }

  private static String clean(String value) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      out.append(c >= 0x20 && c < 0x7f && c != '(' && c != ')' && c != ';' ? c : '_');
    }
    String text = out.toString().trim();
    return text.isEmpty() ? "unknown" : text;
  }
}
