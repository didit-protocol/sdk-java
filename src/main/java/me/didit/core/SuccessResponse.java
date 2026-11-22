package me.didit.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Locale;

/** A 2xx response handed to a {@link ResponseHandler}. */
public final class SuccessResponse {
  final ApiClient client;
  final ApiRequest request;
  final RequestOptions options;
  final URI uri;
  private final TransportResponse response;
  private final byte[] body;

  SuccessResponse(
      ApiClient client,
      ApiRequest request,
      RequestOptions options,
      URI uri,
      TransportResponse response,
      boolean streaming)
      throws IOException {
    this.client = client;
    this.request = request;
    this.options = options;
    this.uri = uri;
    this.response = response;
    this.body = streaming ? new byte[0] : response.readBody();
  }

  /**
   * The HTTP status.
   *
   * @return the status
   */
  public int statusCode() {
    return response.statusCode();
  }

  /**
   * The response headers.
   *
   * @return the headers
   */
  public Headers headers() {
    return response.headers();
  }

  /**
   * The body parsed as JSON.
   *
   * @return the JSON value; JSON null for an empty body
   * @throws me.didit.errors.UnexpectedResponseException when the body is not JSON
   */
  public JsonNode json() {
    if (body.length == 0) {
      return Json.nodes().nullNode();
    }
    String contentType = headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
    if (!contentType.isEmpty() && !contentType.contains("json")) {
      throw new me.didit.errors.UnexpectedResponseException(
          request.describe() + " returned " + contentType + " instead of JSON");
    }
    return Json.parse(body);
  }

  InputStream stream() {
    return response.body();
  }
}
