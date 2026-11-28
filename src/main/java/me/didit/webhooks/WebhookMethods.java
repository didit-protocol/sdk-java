package me.didit.webhooks;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The webhook helpers reachable as {@code client.webhooks().verify(...)}; they do the same as the
 * static methods of {@link Webhooks} and need no API key.
 */
public abstract class WebhookMethods {
  /** Creates the helpers. */
  protected WebhookMethods() {}

  /**
   * Verifies a delivery. See {@link Webhooks#verify(String, Map, String)}.
   *
   * @param rawBody the request body exactly as received
   * @param headers the request headers
   * @param secret the endpoint's webhook secret
   * @return the typed event
   */
  public final WebhookEvent verify(String rawBody, Map<String, ?> headers, String secret) {
    return Webhooks.verify(rawBody, headers, secret);
  }

  /**
   * Verifies a delivery. See {@link Webhooks#verify(byte[], Map, VerifyOptions)}.
   *
   * @param rawBody the request body exactly as received
   * @param headers the request headers
   * @param options the secrets, tolerance and Simple opt-in
   * @return the typed event
   */
  public final WebhookEvent verify(byte[] rawBody, Map<String, ?> headers, VerifyOptions options) {
    return Webhooks.verify(rawBody, headers, options);
  }

  /**
   * Verifies a delivery. See {@link Webhooks#verify(byte[], Function, VerifyOptions)}.
   *
   * @param rawBody the request body exactly as received
   * @param headers returns a header's value by name, or {@code null}
   * @param options the secrets, tolerance and Simple opt-in
   * @return the typed event
   */
  public final WebhookEvent verify(
      byte[] rawBody, Function<String, @Nullable String> headers, VerifyOptions options) {
    return Webhooks.verify(rawBody, headers, options);
  }

  /**
   * Verifies a parsed delivery. See {@link Webhooks#verifyParsed(JsonNode, Map, String)}.
   *
   * @param payload the parsed JSON body
   * @param headers the request headers
   * @param secret the endpoint's webhook secret
   * @return the typed event
   */
  public final WebhookEvent verifyParsed(JsonNode payload, Map<String, ?> headers, String secret) {
    return Webhooks.verifyParsed(payload, headers, secret);
  }
}
