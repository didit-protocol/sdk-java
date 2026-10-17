package me.didit.core;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One HTTP request as handed to an {@link HttpTransport}. */
public final class TransportRequest {
  private final String method;
  private final URI uri;
  private final Map<String, List<String>> headers;
  private final byte[] body;
  private final Duration timeout;
  private final boolean streamResponse;

  TransportRequest(
      String method,
      URI uri,
      Map<String, List<String>> headers,
      byte[] body,
      Duration timeout,
      boolean streamResponse) {
    this.method = method;
    this.uri = uri;
    this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    this.body = body;
    this.timeout = timeout;
    this.streamResponse = streamResponse;
  }

  /**
   * Creates a request whose response is read into memory.
   *
   * @param method the HTTP method
   * @param uri the absolute URL
   * @param headers header names to values
   * @param body the body; empty for none
   * @param timeout the time to wait for the response
   * @return the request
   */
  public static TransportRequest of(
      String method, URI uri, Map<String, List<String>> headers, byte[] body, Duration timeout) {
    return new TransportRequest(method, uri, headers, body.clone(), timeout, false);
  }

  /**
   * The HTTP method.
   *
   * @return {@code GET}, {@code POST}, {@code PUT}, {@code PATCH} or {@code DELETE}
   */
  public String method() {
    return method;
  }

  /**
   * The absolute URL, with the query string.
   *
   * @return the URL
   */
  public URI uri() {
    return uri;
  }

  /**
   * The request headers, including the credentials.
   *
   * @return header names to values
   */
  public Map<String, List<String>> headers() {
    return headers;
  }

  /**
   * The request body.
   *
   * @return the body bytes; empty when there is no body
   */
  public byte[] body() {
    return body.clone();
  }

  /**
   * The time to wait for the response.
   *
   * @return the timeout
   */
  public Duration timeout() {
    return timeout;
  }

  /**
   * Whether the caller reads the response body as a stream (binary responses). When false the
   * transport may buffer the body.
   *
   * @return true for streamed responses
   */
  public boolean streamResponse() {
    return streamResponse;
  }

  @Override
  public String toString() {
    return "TransportRequest{"
        + method
        + " "
        + uri.getScheme()
        + "://"
        + uri.getAuthority()
        + uri.getRawPath()
        + "}";
  }
}
