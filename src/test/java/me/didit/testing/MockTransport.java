package me.didit.testing;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import me.didit.Didit;
import me.didit.core.Headers;
import me.didit.core.HttpTransport;
import me.didit.core.TransportRequest;
import me.didit.core.TransportResponse;

/** An {@link HttpTransport} that records requests and replays queued responses. */
public final class MockTransport implements HttpTransport {
  public static final String ORGANIZATION_ID = "00000000-0000-4000-8000-0000000000a1";
  public static final String APPLICATION_ID = "00000000-0000-4000-8000-0000000000a2";

  private final Deque<Function<TransportRequest, CompletableFuture<TransportResponse>>> responses =
      new ArrayDeque<>();
  private final List<RecordedRequest> requests = new ArrayList<>();

  /** A client with test credentials that sends through this transport. */
  public Didit client() {
    return Didit.builder()
        .apiKey("test-api-key")
        .accountToken("test-account-token")
        .organizationId(ORGANIZATION_ID)
        .applicationId(APPLICATION_ID)
        .environment(name -> null)
        .transport(this)
        .build();
  }

  public MockTransport respond(int status, String body) {
    return respond(status, Map.of("Content-Type", "application/json"), body);
  }

  public MockTransport respond(int status, Map<String, String> headers, String body) {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    Headers h = headers(headers);
    responses.add(r -> CompletableFuture.completedFuture(new TransportResponse(status, h, bytes)));
    return this;
  }

  public MockTransport respondJson(int status, JsonNode body) {
    return respond(status, body.toString());
  }

  public MockTransport respondBinary(int status, String contentType, byte[] body) {
    Headers h = headers(Map.of("Content-Type", contentType));
    responses.add(r -> CompletableFuture.completedFuture(new TransportResponse(status, h, body)));
    return this;
  }

  public MockTransport fail(IOException error) {
    responses.add(r -> CompletableFuture.failedFuture(error));
    return this;
  }

  public MockTransport answer(Function<TransportRequest, CompletableFuture<TransportResponse>> f) {
    responses.add(f);
    return this;
  }

  public static Headers headers(Map<String, String> headers) {
    Map<String, List<String>> map = new java.util.LinkedHashMap<>();
    headers.forEach((k, v) -> map.put(k, List.of(v)));
    return Headers.of(map);
  }

  @Override
  public synchronized CompletableFuture<TransportResponse> send(TransportRequest request) {
    requests.add(new RecordedRequest(request));
    Function<TransportRequest, CompletableFuture<TransportResponse>> next = responses.poll();
    if (next == null) {
      throw new AssertionError("Unexpected request: " + request);
    }
    return next.apply(request);
  }

  public synchronized List<RecordedRequest> requests() {
    return List.copyOf(requests);
  }

  /** The only request sent; fails when there were none or several. */
  public synchronized RecordedRequest only() {
    if (requests.size() != 1) {
      throw new AssertionError("Expected exactly one request, got " + requests.size());
    }
    return requests.get(0);
  }
}
