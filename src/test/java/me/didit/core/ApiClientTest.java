package me.didit.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import me.didit.errors.ApiConnectionException;
import me.didit.errors.ApiStatusException;
import me.didit.errors.ApiTimeoutException;
import me.didit.errors.AuthenticationException;
import me.didit.errors.BadRequestException;
import me.didit.errors.ConfigurationException;
import me.didit.errors.ConflictException;
import me.didit.errors.InternalServerException;
import me.didit.errors.NotFoundException;
import me.didit.errors.PermissionDeniedException;
import me.didit.errors.RateLimitException;
import me.didit.errors.UnexpectedResponseException;
import me.didit.errors.UnprocessableEntityException;
import me.didit.testing.MockTransport;
import me.didit.testing.RecordedRequest;
import me.didit.testing.TestJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class ApiClientTest {
  private final MockTransport transport = new MockTransport();
  private final List<Duration> delays = new ArrayList<>();

  private ClientOptions.Builder options() {
    return ClientOptions.builder()
        .apiKey("test-api-key")
        .organizationId("org-1")
        .applicationId("app-1")
        .environment(name -> null)
        .transport(transport)
        .scheduler(
            delay -> {
              delays.add(delay);
              Executor now = Runnable::run;
              return now;
            });
  }

  private ApiClient client() {
    return new ApiClient(options().build());
  }

  private static final ResponseHandler<JsonNode> JSON = Responses.jsonNode();

  private static ApiRequest get(String path) {
    return ApiRequest.builder("GET", path).build();
  }

  @Test
  void sendsTheStandardHeaders() {
    transport.respond(200, "{\"ok\":true}");
    RawResponse<JsonNode> raw = client().execute(get("/v3/x/"), RequestOptions.none(), JSON);
    RecordedRequest r = transport.only();
    assertEquals("test-api-key", r.header("x-api-key"));
    assertEquals("application/json", r.header("Accept"));
    assertTrue(
        r.header("User-Agent").matches("didit-java/\\d+\\.\\d+\\.\\d+.* \\(Java [^;]+; [^)]+\\)"),
        r.header("User-Agent"));
    assertEquals("java/" + Version.VERSION, r.header("X-Didit-Client"));
    assertNull(r.header("Content-Type"));
    assertEquals("https://verification.didit.me/v3/x/", r.uri().toString());
    assertEquals(200, raw.statusCode());
    assertTrue(raw.data().get("ok").asBoolean());
    assertTrue(raw.toString().contains("200"));
  }

  @Test
  void requestOptionsOverrideTheClient() {
    transport.respond(200, "{}");
    ApiClient client =
        new ApiClient(options().defaultHeader("X-Team", "a").defaultHeader("X-Other", "1").build());
    client.execute(
        ApiRequest.builder(
                "POST", "/v3/organization/{organization_id}/application/{application_id}/x/")
            .applicationScoped()
            .build(),
        RequestOptions.builder()
            .apiKey("other-key")
            .organizationId("org-2")
            .applicationId("app-2")
            .header("x-team", "b")
            .idempotencyKey("idem-1")
            .queryParam("debug", "1")
            .timeout(Duration.ofSeconds(5))
            .maxRetries(0)
            .build(),
        JSON);
    RecordedRequest r = transport.only();
    assertEquals("other-key", r.header("x-api-key"));
    assertEquals("/v3/organization/org-2/application/app-2/x/", r.path());
    assertEquals("b", r.header("X-Team"));
    assertEquals("1", r.header("X-Other"));
    assertEquals("idem-1", r.header("Idempotency-Key"));
    assertEquals(Map.of("debug", List.of("1")), r.query());
    assertEquals(Duration.ofSeconds(5), r.raw().timeout());
  }

  @Test
  void configurationErrorsHappenBeforeSending() {
    ApiClient noKey = new ApiClient(options().environment(n -> null).build().toBuilder().build());
    ClientOptions withoutKey =
        ClientOptions.builder().environment(name -> null).transport(transport).build();
    ConfigurationException e =
        assertThrows(
            ConfigurationException.class,
            () -> new ApiClient(withoutKey).execute(get("/v3/x/"), RequestOptions.none(), JSON));
    assertTrue(e.getMessage().contains("DIDIT_API_KEY"));
    assertThrows(
        ConfigurationException.class,
        () ->
            new ApiClient(withoutKey)
                .execute(
                    ApiRequest.builder(
                            "GET",
                            "/v3/organization/{organization_id}/application/{application_id}/x/")
                        .auth(ApiRequest.Auth.NONE)
                        .applicationScoped()
                        .build(),
                    RequestOptions.none(),
                    JSON));
    assertThrows(
        ConfigurationException.class,
        () ->
            new ApiClient(withoutKey)
                .execute(
                    ApiRequest.builder("GET", "/x/")
                        .account()
                        .auth(ApiRequest.Auth.ACCOUNT_TOKEN)
                        .build(),
                    RequestOptions.none(),
                    JSON));
    for (String bad : new String[] {"", ".", ".."}) {
      assertThrows(
          ConfigurationException.class,
          () ->
              noKey.execute(
                  ApiRequest.builder("GET", "/v3/session/{id}/").pathParam("id", bad).build(),
                  RequestOptions.none(),
                  JSON));
    }
    assertThrows(
        ConfigurationException.class,
        () ->
            noKey.execute(
                ApiRequest.builder("GET", "/v3/session/{id}/").build(),
                RequestOptions.none(),
                JSON));
    assertEquals(0, transport.requests().size());
  }

  @Test
  void pathParametersAreEncodedAsOneSegment() {
    transport.respond(200, "{}");
    client()
        .execute(
            ApiRequest.builder("GET", "/v3/session/{id}/x/").pathParam("id", "a/b c?é").build(),
            RequestOptions.none(),
            JSON);
    assertEquals("/v3/session/a%2Fb%20c%3F%C3%A9/x/", transport.only().uri().getRawPath());
  }

  @Test
  void queryAndHeaderFieldsComeFromTheParameters() {
    transport.respond(200, "{}");
    Pet params =
        Pet.builder()
            .name("Rex")
            .age(3)
            .tags(List.of("a", "b"))
            .nickname(null)
            .extra(Map.of("k", 1.5))
            .build();
    client()
        .execute(
            ApiRequest.builder("PATCH", "/v3/pets/")
                .params(params)
                .queryFields("tags", "nickname")
                .queryField("years", "age")
                .headerField("X-Pet-Name", "name")
                .body(ApiRequest.BodyEncoding.JSON)
                .build(),
            RequestOptions.none(),
            JSON);
    RecordedRequest r = transport.only();
    assertEquals(Map.of("tags", List.of("a", "b"), "years", List.of("3")), r.query());
    assertEquals("Rex", r.header("X-Pet-Name"));
    assertEquals("application/json", r.header("Content-Type"));
    TestJson.assertJsonEquals("{\"extra\":{\"k\":1.5}}", r.body());
  }

  @Test
  void multipartBodies() {
    transport.respond(200, "{}").respond(200, "{}");
    Pet params =
        Pet.builder()
            .name("Rex")
            .age(3)
            .tags(List.of("a", "b"))
            .nickname(null)
            .extra(Map.of("k", List.of(Map.of("x", true))))
            .kind(Pet.Kind.DOG)
            .photo(FileUpload.of(new byte[] {1, 2, 3}, "rex.jpg"))
            .build();
    ApiRequest request =
        ApiRequest.builder("POST", "/v3/pets/")
            .params(params)
            .body(ApiRequest.BodyEncoding.JSON_OR_MULTIPART)
            .build();
    client().execute(request, RequestOptions.none(), JSON);
    RecordedRequest r = transport.requests().get(0);
    assertTrue(r.header("Content-Type").startsWith("multipart/form-data; boundary=didit-"));
    assertEquals(
        Map.of(
            "name", List.of("Rex"),
            "age", List.of("3"),
            "tags", List.of("a", "b"),
            "extra", List.of("{\"k\":[{\"x\":true}]}"),
            "kind", List.of("dog")),
        r.multipartFields());
    assertEquals(Map.of("photo", List.of("rex.jpg")), r.multipartFiles());
    RecordedRequest.Part photo =
        r.parts().stream().filter(p -> p.fileName != null).findFirst().orElseThrow();
    assertEquals("image/jpeg", photo.contentType);
    // Without a file, the same operation sends JSON.
    client()
        .execute(
            ApiRequest.builder("POST", "/v3/pets/")
                .params(Pet.builder().name("Rex").build())
                .body(ApiRequest.BodyEncoding.JSON_OR_MULTIPART)
                .build(),
            RequestOptions.none(),
            JSON);
    assertEquals("application/json", transport.requests().get(1).header("Content-Type"));
  }

  @Test
  void accountOperationsUseTheAccountTokenAndNeverTheApiKey() {
    transport.respond(200, "[]");
    ApiClient client = new ApiClient(options().accountToken("acct-1").build());
    client.execute(
        ApiRequest.builder("GET", "/organizations/me/")
            .account()
            .auth(ApiRequest.Auth.ACCOUNT_TOKEN)
            .build(),
        RequestOptions.none(),
        JSON);
    RecordedRequest r = transport.only();
    assertEquals("https://apx.didit.me/auth/v2/organizations/me/", r.uri().toString());
    assertEquals("Bearer acct-1", r.header("Authorization"));
    assertNull(r.header("x-api-key"));
    transport.respond(200, "{}");
    client.execute(
        ApiRequest.builder("POST", "/programmatic/login/")
            .account()
            .auth(ApiRequest.Auth.NONE)
            .build(),
        RequestOptions.builder().accountToken("ignored").build(),
        JSON);
    RecordedRequest login = transport.requests().get(1);
    assertNull(login.header("Authorization"));
    assertNull(login.header("x-api-key"));
  }

  @ParameterizedTest
  @CsvSource({
    "400,BadRequestException",
    "401,AuthenticationException",
    "403,PermissionDeniedException",
    "404,NotFoundException",
    "409,ConflictException",
    "422,UnprocessableEntityException",
    "429,RateLimitException",
    "500,InternalServerException",
    "503,InternalServerException",
    "418,ApiStatusException"
  })
  void statusErrors(int status, String type) {
    for (int i = 0; i < 3; i++) {
      transport.respond(
          status,
          Map.of("Content-Type", "application/json", "X-Request-Id", "req-9"),
          "{\"detail\":\"Nope\"}");
    }
    ApiStatusException e =
        assertThrows(
            ApiStatusException.class,
            () ->
                client()
                    .execute(
                        ApiRequest.builder("POST", "/v3/x/").build(), RequestOptions.none(), JSON));
    assertEquals(type, e.getClass().getSimpleName());
    assertEquals(status, e.statusCode());
    assertEquals("Nope", e.body().orElseThrow().get("detail").asText());
    assertEquals("req-9", e.requestId().orElseThrow());
    assertEquals("req-9", e.headers().requestId().orElseThrow());
    assertTrue(e.getMessage().contains("POST /v3/x/"));
    assertTrue(e.getMessage().contains("Nope"));
    assertTrue(e.getMessage().contains("req-9"));
    assertFalse(e.getMessage().contains("test-api-key"));
  }

  @Test
  void errorBodiesThatAreNotJson() {
    transport.respond(404, Map.of("Content-Type", "text/html"), "<h1>" + "x".repeat(400) + "</h1>");
    NotFoundException e =
        assertThrows(
            NotFoundException.class,
            () -> client().execute(get("/v3/x/"), RequestOptions.none(), JSON));
    assertTrue(e.body().orElseThrow().isTextual());
    assertTrue(e.getMessage().endsWith("..."));
    transport.respond(400, Map.of(), "");
    BadRequestException empty =
        assertThrows(
            BadRequestException.class,
            () -> client().execute(get("/v3/x/"), RequestOptions.none(), JSON));
    assertTrue(empty.body().isEmpty());
    assertTrue(empty.requestId().isEmpty());
    transport.respond(422, "{\"field\":[\"bad\"]}");
    UnprocessableEntityException fields =
        assertThrows(
            UnprocessableEntityException.class,
            () -> client().execute(get("/v3/x/"), RequestOptions.none(), JSON));
    assertTrue(fields.getMessage().contains("field"));
  }

  @Test
  void retriesIdempotentRequestsOnServerErrors() {
    transport.respond(503, "{}").respond(502, "{}").respond(200, "{\"ok\":1}");
    RawResponse<JsonNode> r = client().execute(get("/v3/x/"), RequestOptions.none(), JSON);
    assertEquals(200, r.statusCode());
    assertEquals(3, transport.requests().size());
    assertEquals(2, delays.size());
    assertTrue(delays.get(0).toMillis() <= 500);
    assertTrue(delays.get(1).toMillis() <= 1000);
  }

  @Test
  void givesUpAfterMaxRetries() {
    for (int i = 0; i < 3; i++) {
      transport.respond(500, "{}");
    }
    assertThrows(
        InternalServerException.class,
        () -> client().execute(get("/v3/x/"), RequestOptions.none(), JSON));
    assertEquals(3, transport.requests().size());
  }

  @Test
  void neverRetriesPostOrPatchAfterTheRequestMayHaveArrived() {
    transport.respond(503, "{}");
    assertThrows(
        InternalServerException.class,
        () ->
            client()
                .execute(
                    ApiRequest.builder("POST", "/v3/x/").build(), RequestOptions.none(), JSON));
    assertEquals(1, transport.requests().size());
    transport.respond(408, "{}");
    assertThrows(
        ApiStatusException.class,
        () ->
            client()
                .execute(
                    ApiRequest.builder("PATCH", "/v3/x/").build(), RequestOptions.none(), JSON));
    assertEquals(2, transport.requests().size());
    transport.fail(new IOException("connection reset"));
    ApiConnectionException e =
        assertThrows(
            ApiConnectionException.class,
            () ->
                client()
                    .execute(
                        ApiRequest.builder("POST", "/v3/x/").build(), RequestOptions.none(), JSON));
    assertTrue(e.getMessage().contains("connection reset"));
    assertEquals(3, transport.requests().size());
    transport.fail(new HttpTimeoutException("read timed out"));
    assertThrows(
        ApiTimeoutException.class,
        () ->
            client()
                .execute(
                    ApiRequest.builder("POST", "/v3/x/").build(), RequestOptions.none(), JSON));
    assertEquals(4, transport.requests().size());
  }

  @Test
  void retriesPostOnRateLimitsAndConnectionsThatNeverOpened() {
    transport
        .respond(429, Map.of("Retry-After", "2"), "{}")
        .fail(new ConnectException("refused"))
        .respond(201, "{}");
    RawResponse<JsonNode> r =
        client().execute(ApiRequest.builder("POST", "/v3/x/").build(), RequestOptions.none(), JSON);
    assertEquals(201, r.statusCode());
    assertEquals(3, transport.requests().size());
    assertEquals(Duration.ofSeconds(2), delays.get(0));
    transport.fail(new HttpConnectTimeoutException("connect timed out")).respond(200, "{}");
    client().execute(ApiRequest.builder("PATCH", "/v3/x/").build(), RequestOptions.none(), JSON);
    assertEquals(5, transport.requests().size());
  }

  @Test
  void retriesGetOnConnectionErrorsAndTimeouts() {
    transport
        .fail(new IOException("reset"))
        .fail(new HttpTimeoutException("slow"))
        .respond(200, "{}");
    client().execute(get("/v3/x/"), RequestOptions.none(), JSON);
    assertEquals(3, transport.requests().size());
    for (int i = 0; i < 3; i++) {
      transport.fail(new HttpTimeoutException("slow"));
    }
    assertThrows(
        ApiTimeoutException.class,
        () -> client().execute(get("/v3/x/"), RequestOptions.none(), JSON));
    transport.fail(new HttpConnectTimeoutException("connect"));
    ApiConnectionException e =
        assertThrows(
            ApiConnectionException.class,
            () ->
                client()
                    .execute(get("/v3/x/"), RequestOptions.builder().maxRetries(0).build(), JSON));
    assertFalse(e instanceof ApiTimeoutException);
  }

  @Test
  void aTransportBugIsNotRetried() {
    transport.answer(
        r -> {
          throw new IllegalStateException("bug");
        });
    ApiConnectionException e =
        assertThrows(
            ApiConnectionException.class,
            () -> client().execute(get("/v3/x/"), RequestOptions.none(), JSON));
    assertInstanceOf(IllegalStateException.class, e.getCause());
    assertEquals(1, transport.requests().size());
  }

  @Test
  void timesOutWhenNoResponseArrives() {
    transport.answer(r -> new CompletableFuture<>());
    ApiTimeoutException e =
        assertThrows(
            ApiTimeoutException.class,
            () ->
                client()
                    .execute(
                        ApiRequest.builder("POST", "/v3/x/").build(),
                        RequestOptions.builder().timeout(Duration.ofMillis(50)).build(),
                        JSON));
    assertTrue(e.getMessage().contains("50 ms"));
  }

  @Test
  void retryAfterParsing() {
    ZonedDateTime now = ZonedDateTime.parse("2026-11-01T10:00:00Z");
    assertEquals(
        Optional.of(Duration.ofMillis(1500)), ApiClient.RetryPolicy.parseRetryAfter("1.5", now));
    assertEquals(
        Optional.of(Duration.ofSeconds(30)),
        ApiClient.RetryPolicy.parseRetryAfter(
            now.plusSeconds(30).format(DateTimeFormatter.RFC_1123_DATE_TIME), now));
    assertEquals(
        Optional.of(Duration.ZERO),
        ApiClient.RetryPolicy.parseRetryAfter(
            now.minusSeconds(30).format(DateTimeFormatter.RFC_1123_DATE_TIME), now));
    assertEquals(Optional.empty(), ApiClient.RetryPolicy.parseRetryAfter("soon", now));
    assertEquals(Optional.empty(), ApiClient.RetryPolicy.parseRetryAfter("-1", now));
    assertEquals(Optional.empty(), ApiClient.RetryPolicy.parseRetryAfter("NaN", now));
    assertEquals(
        Duration.ofSeconds(60), ApiClient.RetryPolicy.delay(0, Optional.of("3600"), 0.5, now));
    assertEquals(
        Duration.ofMillis(250), ApiClient.RetryPolicy.delay(0, Optional.empty(), 0.5, now));
    assertEquals(
        Duration.ofMillis(500), ApiClient.RetryPolicy.delay(1, Optional.of("x"), 0.5, now));
    assertEquals(
        Duration.ofSeconds(8), ApiClient.RetryPolicy.delay(10, Optional.empty(), 1.0, now));
    assertTrue(ApiClient.RetryPolicy.retryableStatus(429, false));
    assertFalse(ApiClient.RetryPolicy.retryableStatus(500, false));
    assertTrue(ApiClient.RetryPolicy.retryableStatus(408, true));
    assertFalse(ApiClient.RetryPolicy.retryableStatus(404, true));
    assertTrue(
        ApiClient.RetryPolicy.failedBeforeSending(
            new IOException(new java.net.UnknownHostException("x"))));
    assertFalse(ApiClient.RetryPolicy.failedBeforeSending(new IOException("reset")));
  }

  @Test
  void unexpectedSuccessBodies() {
    transport.respond(200, Map.of("Content-Type", "text/html"), "<html></html>");
    assertThrows(
        UnexpectedResponseException.class,
        () -> client().execute(get("/v3/x/"), RequestOptions.none(), Responses.json(Conv.STRING)));
    transport.respond(200, Map.of(), "");
    assertThrows(
        UnexpectedResponseException.class,
        () -> client().execute(get("/v3/x/"), RequestOptions.none(), Responses.json(Conv.STRING)));
    transport.respond(200, Map.of(), "{bad json");
    assertThrows(
        UnexpectedResponseException.class,
        () -> client().execute(get("/v3/x/"), RequestOptions.none(), JSON));
    transport.respond(200, "[1]");
    assertThrows(
        UnexpectedResponseException.class,
        () ->
            client()
                .execute(
                    get("/v3/x/"),
                    RequestOptions.none(),
                    Responses.json(Conv.model(Pet::fromJson))));
    transport.respond(204, Map.of(), "");
    assertNull(
        client()
            .execute(
                ApiRequest.builder("DELETE", "/v3/x/").build(),
                RequestOptions.none(),
                Responses.none())
            .data());
  }

  @Test
  void typedAndStatusDependentBodies() {
    transport.respond(200, "{\"name\":\"Rex\",\"age\":\"4\"}");
    Pet pet =
        client()
            .execute(
                get("/v3/pet/"), RequestOptions.none(), Responses.json(Conv.model(Pet::fromJson)))
            .data();
    assertEquals("Rex", pet.name());
    assertEquals(4L, pet.age().orElseThrow());
    transport.respond(202, "{\"job\":1}");
    Pet.Outcome outcome =
        client()
            .execute(
                get("/v3/pet/"),
                RequestOptions.none(),
                Responses.byStatus(Pet.Outcome::fromResponse))
            .data();
    assertEquals(202, outcome.statusCode());
    assertEquals(1, outcome._json().get("job").asInt());
    assertTrue(outcome.toString().contains("202"));
    assertTrue(outcome.equals(Pet.Outcome.fromResponse(202, TestJson.parse("{\"job\":1}"))));
    assertFalse(outcome.equals(Pet.Outcome.fromResponse(200, TestJson.parse("{\"job\":1}"))));
    assertEquals(
        outcome.hashCode(),
        Pet.Outcome.fromResponse(202, TestJson.parse("{\"job\":1}")).hashCode());
  }

  @Test
  void binaryResponsesStream() throws Exception {
    transport.respondBinary(200, "application/pdf", "%PDF-1.7".getBytes(StandardCharsets.UTF_8));
    BinaryResponse pdf =
        client()
            .execute(
                ApiRequest.builder("GET", "/v3/session/{id}/generate-pdf/")
                    .pathParam("id", "s")
                    .accept("application/pdf")
                    .build(),
                RequestOptions.none(),
                Responses.binary())
            .data();
    assertEquals("application/pdf, application/json", transport.only().header("Accept"));
    assertTrue(transport.only().raw().streamResponse());
    assertEquals("application/pdf", pdf.contentType().orElseThrow());
    assertEquals("%PDF-1.7", new String(pdf.bytes(), StandardCharsets.UTF_8));
    assertThrows(IllegalStateException.class, pdf::bytes);
    assertTrue(pdf.toString().contains("application/pdf"));

    BinaryResponse file =
        BinaryResponse.of(
            MockTransport.headers(
                Map.of("Content-Disposition", "attachment; filename=\"report.pdf\"")),
            new byte[] {1, 2});
    assertEquals("report.pdf", file.fileName().orElseThrow());
    java.nio.file.Path target = java.nio.file.Files.createTempFile("didit", ".pdf");
    try {
      assertEquals(2, file.writeTo(target));
      assertEquals(2, java.nio.file.Files.size(target));
    } finally {
      java.nio.file.Files.delete(target);
    }
    BinaryResponse stream = BinaryResponse.of(Headers.empty(), new byte[] {7});
    try (java.io.InputStream in = stream.stream()) {
      assertEquals(7, in.read());
    }
    stream.close();
    assertTrue(BinaryResponse.of(Headers.empty(), new byte[0]).fileName().isEmpty());
    assertTrue(
        BinaryResponse.of(
                MockTransport.headers(Map.of("Content-Disposition", "inline; filename=")),
                new byte[0])
            .fileName()
            .isEmpty());
    assertEquals(
        "a.csv",
        BinaryResponse.of(
                MockTransport.headers(Map.of("Content-Disposition", "attachment; FILENAME=a.csv")),
                new byte[0])
            .fileName()
            .orElseThrow());
  }

  @Test
  void asyncExecutionAndCancellation() throws Exception {
    transport.respond(200, "{\"a\":1}");
    CompletableFuture<RawResponse<JsonNode>> f =
        client().executeAsync(get("/v3/x/"), RequestOptions.none(), JSON);
    assertEquals(1, f.get().data().get("a").asInt());
    CompletableFuture<me.didit.core.TransportResponse> never = new CompletableFuture<>();
    transport.answer(r -> never);
    CompletableFuture<RawResponse<JsonNode>> pending =
        client().executeAsync(get("/v3/x/"), RequestOptions.none(), JSON);
    pending.cancel(true);
    assertTrue(never.isCancelled());
    CompletableFuture<RawResponse<JsonNode>> failed =
        new ApiClient(ClientOptions.builder().environment(n -> null).transport(transport).build())
            .executeAsync(get("/v3/x/"), RequestOptions.none(), JSON);
    ExecutionException e = assertThrows(ExecutionException.class, failed::get);
    assertInstanceOf(ConfigurationException.class, e.getCause());
  }

  @Test
  void awaitHandlesInterruptsAndCheckedFailures() {
    CompletableFuture<String> never = new CompletableFuture<>();
    Thread.currentThread().interrupt();
    assertThrows(ApiConnectionException.class, () -> ApiClient.await(never));
    assertTrue(Thread.interrupted());
    assertTrue(never.isCancelled());
    CompletableFuture<String> checked = CompletableFuture.failedFuture(new Exception("checked"));
    assertEquals(
        "Request failed",
        assertThrows(me.didit.errors.DiditException.class, () -> ApiClient.await(checked))
            .getMessage());
    CompletableFuture<String> error = CompletableFuture.failedFuture(new AssertionError("boom"));
    assertThrows(AssertionError.class, () -> ApiClient.await(error));
  }

  @Test
  void debugLoggingNeverFails() {
    transport.respond(503, "{}").respond(200, "{}").respond(404, "{}").fail(new IOException("x"));
    ApiClient client = new ApiClient(options().debugLogging(true).build());
    assertTrue(client.options().debugLogging());
    client.execute(get("/v3/x/"), RequestOptions.none(), JSON);
    assertThrows(
        NotFoundException.class, () -> client.execute(get("/v3/x/"), RequestOptions.none(), JSON));
    assertThrows(
        ApiConnectionException.class,
        () ->
            client.execute(
                ApiRequest.builder("POST", "/v3/x/").build(), RequestOptions.none(), JSON));
  }

  @Test
  void handlerFailuresSurfaceAsTheirOwnErrors() {
    transport.respond(200, "{}");
    ResponseHandler<String> broken =
        response -> {
          throw new UnexpectedResponseException("bad shape");
        };
    assertThrows(
        UnexpectedResponseException.class,
        () -> client().execute(get("/v3/x/"), RequestOptions.none(), broken));
    transport.answer(
        r ->
            CompletableFuture.completedFuture(
                new TransportResponse(
                    200,
                    Headers.empty(),
                    new java.io.InputStream() {
                      @Override
                      public int read() throws IOException {
                        throw new IOException("reset while reading");
                      }
                    })));
    assertThrows(
        ApiConnectionException.class,
        () -> client().execute(get("/v3/x/"), RequestOptions.none(), JSON));
  }

  @Test
  void errorBodyReadFailuresStillRaiseTheStatusError() {
    transport.answer(
        r ->
            CompletableFuture.completedFuture(
                new TransportResponse(
                    400,
                    Headers.empty(),
                    new java.io.InputStream() {
                      @Override
                      public int read() throws IOException {
                        throw new IOException("reset");
                      }
                    })));
    BadRequestException e =
        assertThrows(
            BadRequestException.class,
            () -> client().execute(get("/v3/x/"), RequestOptions.none(), JSON));
    assertTrue(e.body().isEmpty());
  }

  @Test
  void unusedConstructorsOfStatusExceptions() {
    Headers h = Headers.empty();
    assertEquals(403, new PermissionDeniedException("m", null, h).statusCode());
    assertEquals(409, new ConflictException("m", null, h).statusCode());
    assertEquals(429, new RateLimitException("m", null, h).statusCode());
    assertEquals(401, new AuthenticationException("m", null, h).statusCode());
    assertTrue(new ApiStatusException("m", 418, TestJson.parse("{\"a\":1}"), h).body().isPresent());
  }

  @Test
  void origins() {
    URI base = URI.create("https://verification.didit.me");
    assertEquals(
        "https://verification.didit.me/v3/sessions/?offset=10",
        ApiClient.Paths.sameOrigin(
                base, URI.create("http://verification.didit.me/v3/sessions/?offset=10"))
            .toString());
    assertEquals(
        "https://verification.didit.me/v3/x/?a=1",
        ApiClient.Paths.sameOrigin(base, URI.create("/v3/x/?a=1")).toString());
    assertThrows(
        UnexpectedResponseException.class,
        () -> ApiClient.Paths.sameOrigin(base, URI.create("https://evil.example.com/v3/")));
    assertThrows(
        UnexpectedResponseException.class,
        () ->
            ApiClient.Paths.sameOrigin(base, URI.create("https://verification.didit.me:8443/v3/")));
    assertEquals("", ApiClient.Paths.queryString(List.of()));
  }
}
