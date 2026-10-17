package me.didit.authentication;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;
import me.didit.errors.ConfigurationException;

/**
 * A publishable key: {@code pk_live_} or {@code pk_test_} followed by the unpadded base64url issuer
 * URL. It is public and safe to ship to browsers and apps.
 */
public final class PublishableKey {
  private static final Pattern BODY = Pattern.compile("[A-Za-z0-9_-]+");

  private final String issuer;
  private final boolean live;

  private PublishableKey(String issuer, boolean live) {
    this.issuer = issuer;
    this.live = live;
  }

  /**
   * Parses a publishable key.
   *
   * @param key the key, for example {@code pk_live_aHR0cHM6Ly9...}
   * @return the issuer and mode
   * @throws ConfigurationException when the key is malformed or its issuer is not valid
   */
  public static PublishableKey parse(String key) {
    String k = key.trim();
    boolean live;
    if (k.startsWith("pk_live_")) {
      live = true;
    } else if (k.startsWith("pk_test_")) {
      live = false;
    } else {
      throw new ConfigurationException("A publishable key starts with pk_live_ or pk_test_");
    }
    String body = k.substring("pk_live_".length());
    if (!BODY.matcher(body).matches() || body.length() % 4 == 1) {
      throw new ConfigurationException("The publishable key is malformed");
    }
    String issuer;
    try {
      byte[] bytes = Base64.getUrlDecoder().decode(body);
      issuer =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (IllegalArgumentException | CharacterCodingException e) {
      throw new ConfigurationException("The publishable key is malformed");
    }
    String normalized = Issuers.normalize(issuer);
    if (live && !normalized.startsWith("https://")) {
      throw new ConfigurationException("A live publishable key must name an https issuer");
    }
    return new PublishableKey(normalized, live);
  }

  /**
   * The issuer URL, without a trailing slash.
   *
   * @return the issuer
   */
  public String issuer() {
    return issuer;
  }

  /**
   * Whether this is a live key ({@code pk_live_}) rather than a sandbox one.
   *
   * @return true for live keys
   */
  public boolean isLive() {
    return live;
  }

  @Override
  public String toString() {
    return "PublishableKey{issuer=" + issuer + ", live=" + live + "}";
  }
}
