package me.didit.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/** One HTTP response as returned by an {@link HttpTransport}. Close it to release the body. */
public final class TransportResponse implements AutoCloseable {
  private final int statusCode;
  private final Headers headers;
  private final InputStream body;

  /**
   * Creates a response.
   *
   * @param statusCode the HTTP status
   * @param headers the response headers
   * @param body the response body; closed by {@link #close()}
   */
  public TransportResponse(int statusCode, Headers headers, InputStream body) {
    this.statusCode = statusCode;
    this.headers = headers;
    this.body = body;
  }

  /**
   * Creates a response with an in-memory body.
   *
   * @param statusCode the HTTP status
   * @param headers the response headers
   * @param body the response body
   */
  public TransportResponse(int statusCode, Headers headers, byte[] body) {
    this(statusCode, headers, new ByteArrayInputStream(body.clone()));
  }

  /**
   * The HTTP status.
   *
   * @return the status
   */
  public int statusCode() {
    return statusCode;
  }

  /**
   * The response headers.
   *
   * @return the headers
   */
  public Headers headers() {
    return headers;
  }

  /**
   * The response body.
   *
   * @return the body stream
   */
  public InputStream body() {
    return body;
  }

  byte[] readBody() throws IOException {
    try (InputStream in = body) {
      return in.readAllBytes();
    }
  }

  @Override
  public void close() {
    try {
      body.close();
    } catch (IOException e) {
      // Nothing useful to do: the response is being discarded.
    }
  }
}
