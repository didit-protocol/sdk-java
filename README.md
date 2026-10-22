# Didit Java / Kotlin SDK

The official Didit Java library, for Java and Kotlin backends.
Identity verification (KYC), business verification (KYB), AML screening, biometrics, transaction monitoring, Travel Rule and customer authentication, from your server, with a typed method for every endpoint of the [Didit API](https://docs.didit.me).

> In development: not released yet. The first release ships with the Didit release of 2026-11-01.

## Install

Java 11 or newer. Gradle (Kotlin DSL):

<!-- x-release-please-start-version -->
```kotlin
implementation("me.didit:didit-java:0.1.0")
```

Maven:

```xml
<dependency>
  <groupId>me.didit</groupId>
  <artifactId>didit-java</artifactId>
  <version>0.1.0</version>
</dependency>
```
<!-- x-release-please-end -->

The only dependencies are Jackson (`jackson-databind`) and JSpecify annotations.

## Quickstart

Create a verification session, read its decision, and verify the webhook Didit sends when it changes:

```java
import me.didit.Didit;
import me.didit.types.SessionsCreateParams;
import me.didit.types.SessionsCreateResponse;
import me.didit.webhooks.SessionStatusUpdatedEvent;
import me.didit.webhooks.WebhookEvent;
import me.didit.webhooks.Webhooks;

Didit client = Didit.builder().apiKey(System.getenv("DIDIT_API_KEY")).build();

// 1. Create a session and send the user to its URL.
SessionsCreateResponse session = client.sessions().create(
    SessionsCreateParams.builder()
        .workflowId("your-workflow-id")
        .vendorData("user-42")
        .callback("https://example.com/verification/done")
        .build());
String url = session.url();

// 2. Read the decision.
var decision = client.sessions().retrieveDecision(session.sessionId());
decision.status().ifPresent(status -> System.out.println("Decision: " + status));

// 3. In your webhook endpoint, verify the raw body before trusting it.
WebhookEvent event = Webhooks.verify(rawBody, headers, System.getenv("DIDIT_WEBHOOK_SECRET"));
if (event instanceof SessionStatusUpdatedEvent) {
  SessionStatusUpdatedEvent e = (SessionStatusUpdatedEvent) event;
  System.out.println(e.sessionId().orElse("?") + " is " + e.status().orElse("?"));
}
```

The same in Kotlin:

```kotlin
val client = Didit.builder().apiKey(System.getenv("DIDIT_API_KEY")).build()
val session = client.sessions().create(
  SessionsCreateParams.builder().workflowId("your-workflow-id").vendorData("user-42").build()
)
val id: String = session.sessionId()                 // required by the API: never null
val vendorData: String? = session.vendorData().getOrNull() // optional: Optional<T> -> T?

when (val event = Webhooks.verify(rawBody, headers, secret)) {
  is SessionStatusUpdatedEvent -> println("${event.sessionId().getOrNull()} is ${event.status().getOrNull()}")
  is UnknownEvent -> println("New event type ${event.webhookType()}")
  else -> {}
}
```

Runnable examples, including webhook receivers for Spring Boot, Javalin, Ktor and the JDK's HTTP server, are in [`examples/`](examples/).

## The API

Every endpoint is a method on a resource, named after the API: `client.sessions().imports().create(params)`.
Every method also has an `...Async` variant that returns a `CompletableFuture`, and the parameters and responses are typed classes in `me.didit.types`.

| Area | Resources |
|---|---|
| Verification sessions | `sessions`, `sessions().imports()`, `sessions().reviews()`, `businessSessions` |
| Standalone checks | `idVerification`, `faceMatch`, `faceSearch`, `passiveLiveness`, `ageEstimation`, `proofOfAddress`, `documentAi`, `aml`, `databaseValidation`, `phone`, `email`, `ip`, `kyb`, `idWallets`, `fraudChecks` |
| Users and businesses | `users`, `businesses`, `amlMonitoring`, `biometricTemplates` |
| Transactions and crypto | `transactions`, `transactions().rules()`, `transactions().travelRule()`, `walletScreening`, `travelRule`, `travelRule().walletAddresses()` |
| Cases and investigations | `cases` (with `notes`, `links`, `checklist`, `requests`, `teams`, `routingRules`, `blueprints`, `regulatoryReports`), `regulatoryReports`, `reportTemplates`, `networks` |
| Configuration | `workflows`, `questionnaires`, `webhooks().destinations()`, `customization`, `billing` |
| Account management | `account` (`login`, `register`, ...), `account().organizations()`, `account().applications()` |

The full reference of each endpoint is at [docs.didit.me](https://docs.didit.me).

## Configuration

```java
Didit client = Didit.builder()
    .apiKey("...")                       // DIDIT_API_KEY
    .organizationId("...")               // DIDIT_ORGANIZATION_ID, for application-scoped endpoints
    .applicationId("...")                // DIDIT_APPLICATION_ID, for application-scoped endpoints
    .timeout(Duration.ofSeconds(60))     // per attempt; default 60 s
    .maxRetries(2)                       // default 2
    .defaultHeader("X-My-Header", "1")
    .build();
```

- Unset values fall back to the environment variables in the comments, so `new Didit()` works with only `DIDIT_API_KEY` set.
- The client is thread-safe: create one per process and reuse it.
- `client.withOptions(o -> o.timeout(Duration.ofMinutes(2)))` derives a client that shares the connection pool.
- Pass your own `java.net.http.HttpClient` with `.httpClient(...)` for proxies or TLS settings; it must not follow redirects.
- Override anything for one call with `RequestOptions`:

  ```java
  client.aml().screen(params, RequestOptions.builder()
      .idempotencyKey("screen-user-42")  // for the endpoints that accept Idempotency-Key
      .timeout(Duration.ofSeconds(10))
      .build());
  ```

- Application-scoped endpoints (cases, networks, regulatory reports, ...) fill the organization and application ids from the options; calling one without them throws `ConfigurationException` before sending anything.
- The API key is only ever sent to the base URL: it never appears in logs, errors or `toString()`. Plain `http` base URLs are allowed only for `localhost`.
- `DIDIT_LOG=debug` (or `.debugLogging(true)`) logs method, path, status, duration and request id of each request to the `me.didit` `System.Logger`. It never logs bodies or headers.

## Errors

Every exception extends `DiditException` (unchecked).

| Exception | When |
|---|---|
| `ApiConnectionException`, `ApiTimeoutException` | No response |
| `ApiStatusException` | Any non-2xx status, with `statusCode()`, `body()`, `headers()` and `requestId()` |
| `BadRequestException` (400), `AuthenticationException` (401), `PermissionDeniedException` (403), `NotFoundException` (404), `ConflictException` (409), `UnprocessableEntityException` (422), `RateLimitException` (429), `InternalServerException` (5xx) | Subclasses of `ApiStatusException` |
| `ConfigurationException` | A missing API key or id, an empty webhook secret, an empty, `.` or `..` path id |
| `UnexpectedResponseException` | A success body that is not JSON, a field with an unexpected type, a next page link to another origin |
| `WebhookVerificationException` | A webhook failed verification; see `reason()` |
| `TokenVerificationException` | An Authentication token failed verification; see `reason()` |

```java
try {
  client.sessions().retrieveDecision(sessionId);
} catch (NotFoundException e) {
  // the session does not exist or is outside the key's scope
} catch (ApiStatusException e) {
  log.warn("Didit returned {} (request id {})", e.statusCode(), e.requestId().orElse("none"));
}
```

## Retries and timeouts

Failed requests are retried up to `maxRetries` times (default 2) with exponential backoff (0.5 s, full jitter, at most 8 s), honouring `Retry-After` up to 60 s.
`GET`, `PUT` and `DELETE` are retried on connection errors, timeouts, 408, 429 and 5xx.
`POST` and `PATCH` are retried only when the connection failed before the request was sent, or on 429, so a request is never sent twice after it may have reached Didit.

## Pagination

List methods return a `Page`. Iterating a page fetches the following pages as you go:

```java
for (WorkflowListItem workflow : client.workflows().list()) {
  System.out.println(workflow.uuid().orElse("?"));
}

Page<SessionsListResponse.Results> page = client.sessions().list(
    SessionsListParams.builder().limit(50).build());
page.results();                       // this page only
page.count();                         // total, when the API reports it
page.autoPager().stream().limit(200); // a Stream over every page
page.forEachAsync(item -> { ... });   // fetches the pages asynchronously
```

The next page link must point to the API's own origin and never back to a page already read.

## Files and binary responses

Upload endpoints take a `Path`, bytes or a stream:

```java
client.idVerification().verify(IdVerificationVerifyParams.builder()
    .frontImage(Path.of("front.jpg"))
    .backImage(FileUpload.of(bytes, "back.jpg", "image/jpeg"))
    .build());
```

PDF reports, CSV templates and attachments come back as a `BinaryResponse`: call `bytes()`, `writeTo(path)` or `stream()` (and close it).

## Raw responses

```java
RawResponse<SessionsCreateResponse> raw = client.sessions().withRawResponse().create(params);
raw.statusCode();
raw.requestId();
raw.data();
```

## Webhooks

```java
WebhookEvent event = Webhooks.verify(rawBody, headers, secret);
```

- `headers` is a `Map` (names match any case, values may be lists) or a lookup function such as `request::getHeader` (Servlet) or `headers::getFirst` (Spring).
- Verify the raw request body. If your framework only gives you the parsed JSON, use `Webhooks.verifyParsed(payload, headers, secret)`, which checks `X-Signature-V2` only.
- During a secret rotation pass both secrets: `VerifyOptions.builder().secret(current).secret(previous).build()`.
- Deliveries older or newer than 5 minutes are rejected; change it with `.tolerance(...)`.
- `X-Signature-Simple` is deprecated and covers the envelope only; `Webhooks.verify` accepts it only with `.allowSimple(true)` (`verifyParsed` never does), and then `event.isBodyAuthenticated()` is false: re-fetch the record from the API before acting on it.
- A failed check throws `WebhookVerificationException` with `reason()`: `missing_signature`, `invalid_signature`, `missing_timestamp`, `timestamp_mismatch`, `timestamp_out_of_tolerance` or `invalid_payload`. Answer it with a 4xx status.

Events are typed: `SessionStatusUpdatedEvent`, `SessionDataUpdatedEvent`, `UserStatusUpdatedEvent`, `UserDataUpdatedEvent`, `BusinessStatusUpdatedEvent`, `BusinessDataUpdatedEvent`, `ActivityCreatedEvent`, `TransactionCreatedEvent`, `TransactionStatusUpdatedEvent`, `TravelRuleStatusUpdatedEvent`, `WorkflowComplianceChangedEvent`, `KybRegistrySearchResolvedEvent`, and `UnknownEvent` for types this version does not know.
Every event has `eventId()`, `webhookType()`, `timestamp()`, `createdAt()`, `applicationId()`, `environment()` and the whole payload in `_json()`.

## Transactions, wallet screening and Travel Rule

```java
TransactionDetail tx = client.transactions().create(TransactionsCreateParams.builder()
    .transactionId("order-1001")
    .transactionCategory(TransactionsCreateParams.TransactionCategory.FINANCE)
    .transactionDetails(TransactionsCreateParams.TransactionDetails.builder()
        .direction(TransactionsCreateParams.TransactionDetails.Direction.OUTBOUND)
        .amount(250.0).currency("EUR").build())
    .subject(TransactionsCreateParams.Subject.builder()
        .vendorData("user-42").fullName("Ana Ruiz").build())
    .build());

WalletScreeningResult wallet = client.walletScreening().screen(
    WalletScreeningScreenParams.builder().walletAddress(address)
        .blockchain(WalletScreeningScreenParams.Blockchain.of("bitcoin")).build());
```

Transaction rules are under `client.transactions().rules()`, the Travel Rule under `client.travelRule()` and `client.transactions().travelRule()`.

## Authentication

Didit Authentication gives your application its own sign-in (an OpenID Connect issuer). The `Authentication` class checks its tokens on your backend; it never uses the API key.

```java
Authentication auth = Authentication.builder()
    .publishableKey("pk_live_...")   // or .issuer("https://your-app.auth.didit.me")
    .clientId("your-application-id") // checked as the audience
    .build();

AccessTokenClaims claims = auth.verifyAccessToken(bearerToken);
String userId = claims.subject();
```

- Verification accepts ES256 only and checks `typ`, `iss`, `aud`, `exp`, `nbf` and `iat` (60 s leeway). Failures throw `TokenVerificationException` with `reason()`.
- The issuer's discovery document and keys are cached as it says (5 minutes today); an unknown key id refetches them at most once a minute. Concurrent verifications share one fetch, and after a failed fetch they fail fast for 5 seconds instead of each calling the issuer. Create one `Authentication` per issuer and reuse it.
- `Authentication.createPkce()`, `createState()` and `createNonce()` start a sign-in; `exchangeCode(code, verifier, redirectUri)` finishes it; `verifyIdToken(idToken, nonce)` checks the ID token (pass `null` for a token from a refresh, which carries no nonce; a token that does carry one is then refused).
- `refresh(refreshToken)` rotates the tokens. Serialise the refreshes of one session: reusing a consumed refresh token revokes the session.
- `getSession(accessToken)` is a live revocation check (rate-limited: do not call it on every request); `revokeSession(accessToken)` signs the user out.
- `createStepUp(accessToken, StepUpParams.builder("payout").amount("250.00").currency("EUR").returnTo(url).build())` asks for a verification before a sensitive action; act only once `retrieveStepUp(accessToken, id).isApproved()`.

## Account management

`client.account().login(...)` returns an account token for the account endpoints: pass it as `.accountToken(...)` on the client or per request.
Account calls go to `https://apx.didit.me/auth/v2` and never receive the API key.

## Language notes

Choices this SDK makes where the shared design leaves room:

- **Types.** Parameters and responses are immutable classes with builders. `build()` checks required fields. Inline objects are nested classes (`SessionsCreateParams.ContactDetails`); array items take the property's name (`SessionsListResponse.Results`).
- **Absent and null.** A getter for a required field returns the plain type and throws `UnexpectedResponseException` if the API leaves it out. Optional or nullable fields return `Optional`. The setter of a nullable field accepts `null`, which sends JSON `null`. `_json()` returns the raw JSON, so you can tell absent from `null`. `_additionalProperties()` holds the fields this version does not know yet.
- **Free-form JSON.** Read as `JsonNode` or `Map<String, JsonNode>`; written from plain Java maps, lists and values.
- **Enums are open.** Each enum is a class with constants for the known values. `of(String)` accepts any value, and `isKnown()` says whether this version declares it.
- **Unions** (`oneOf`) have `isX()` and `asX()` per variant, plus getters for the fields every variant shares. Responses whose body depends on the status (200 or 202) have `asOk()` and `asAccepted()`.
- **Numbers and dates.** Integers are `long`, numbers `double`, and amounts that the API sends as strings stay `String`. Date-times are `OffsetDateTime` (a value without an offset is read as UTC), dates `LocalDate`.
- **Exceptions** end in `Exception` (`ApiStatusException`, not `APIStatusError`), because in Java `Error` means an unrecoverable JVM failure.
- **Parameters.**
  - Path ids are positional arguments; everything else is in one parameters object.
  - A query parameter with the same name as a body field gets a `Query` suffix (`nodeIdQuery`).
  - `Idempotency-Key` is a request option.
  - Endpoints that take JSON or multipart send multipart only when a file is set.
- **Nullability.** Every package is JSpecify `@NullMarked`, so Kotlin sees `T?` exactly where Java may pass or receive `null`.
- **Async and raw responses.** Every method has an `Async` variant returning a `CompletableFuture`; `withRawResponse()` gives the status, headers and request id. Lists that page with `next_offset` (case action receipts, duplicate search) return plain models.
- **Webhooks.** `Webhooks` is static and needs no client; `client.webhooks().verify(...)` does the same. `created_at` is unix seconds (`Long`).
- **Authentication.** It uses the JDK's own crypto (no JWT library). It never retries, and every call has an `Async` variant.

## Supported runtimes and versioning

- Java 11, 17, 21 and 25 on Linux, macOS and Windows; Kotlin 2.x.
- Semantic versioning: a breaking change to the public SDK surface is a major version. Until 1.0.0, versions are `0.x`.
- The SDK is generated from the OpenAPI documents Didit publishes at docs.didit.me and is updated when they change: endpoints the API removes stay one minor version, deprecated, before they go.
- Releases and their notes are in [CHANGELOG.md](CHANGELOG.md).

## Links

- API reference and guides: [docs.didit.me](https://docs.didit.me)
- Webhooks: [docs.didit.me/integration/webhooks](https://docs.didit.me/integration/webhooks)
- Design shared by every Didit server SDK: [docs/DESIGN.md](docs/DESIGN.md)
- Contributing: [CONTRIBUTING.md](CONTRIBUTING.md) · Security: [SECURITY.md](SECURITY.md)

## License

MIT, see [LICENSE](LICENSE).
