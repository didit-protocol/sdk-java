package me.didit.authentication;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import me.didit.errors.ConfigurationException;

/** Issuer URL rules: https, or http on loopback only; no user info, query or fragment. */
final class Issuers {
  private Issuers() {}

  static String normalize(String issuer) {
    String value = issuer;
    while (value.endsWith("/")) {
      value = value.substring(0, value.length() - 1);
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c <= 0x20 || c == 0x7f) {
        throw new ConfigurationException(
            "The issuer must not contain spaces or control characters");
      }
    }
    URI uri;
    try {
      uri = new URI(value);
    } catch (URISyntaxException e) {
      throw new ConfigurationException("The issuer is not a valid URL");
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    String host = uri.getHost();
    if (host == null
        || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null) {
      throw new ConfigurationException("The issuer must be an https URL without query or fragment");
    }
    if (scheme.equals("http")) {
      if (!isLoopback(host)) {
        throw new ConfigurationException("The issuer must use https (http only for localhost)");
      }
    } else if (!scheme.equals("https")) {
      throw new ConfigurationException("The issuer must use https");
    }
    return value;
  }

  static boolean isLoopback(String host) {
    String h = host.toLowerCase(Locale.ROOT);
    return h.equals("localhost")
        || h.endsWith(".localhost")
        || h.equals("127.0.0.1")
        || h.equals("[::1]")
        || h.equals("::1");
  }

  static String origin(URI uri) {
    int port = uri.getPort();
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (port == -1) {
      port = scheme.equals("http") ? 80 : 443;
    }
    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    return scheme + "://" + host + ":" + port;
  }
}
