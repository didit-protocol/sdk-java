package me.didit.examples

import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.concurrent.CompletableFuture
import kotlin.jvm.optionals.getOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.didit.Didit
import me.didit.core.Headers
import me.didit.core.HttpTransport
import me.didit.core.TransportRequest
import me.didit.core.TransportResponse
import me.didit.errors.NotFoundException
import me.didit.types.SessionsListResponse
import me.didit.types.TransactionsCreateParams
import me.didit.webhooks.SessionStatusUpdatedEvent
import me.didit.webhooks.Webhooks

/**
 * The public API from Kotlin. This file compiles with -Xjspecify-annotations=strict and warnings as
 * errors, so Kotlin sees the library's nullability: passing null where the API takes it, and plain
 * types for required values.
 */
class KotlinApiTest {
  private class Stub(private vararg val bodies: Pair<Int, String>) : HttpTransport {
    val requests = mutableListOf<TransportRequest>()

    override fun send(request: TransportRequest): CompletableFuture<TransportResponse> {
      val (status, body) = bodies[requests.size]
      requests += request
      val headers = Headers.of(mapOf("Content-Type" to listOf("application/json")))
      return CompletableFuture.completedFuture(
        TransportResponse(status, headers, body.toByteArray(StandardCharsets.UTF_8)),
      )
    }
  }

  private fun client(stub: Stub): Didit =
    Didit.builder().apiKey("test-api-key").environment { null }.transport(stub).build()

  @Test
  fun createsASessionAndReadsTypedFields() {
    val stub =
      Stub(
        201 to
          """{"session_id":"s-1","session_number":7,"session_token":"t","url":"https://verify.didit.me/s",
            "vendor_data":null,"metadata":null,"status":"Not Started","callback":null,
            "workflow_id":"w-1","workflow_version":2}""",
      )
    val id = createSession(client(stub), "w-1")
    assertEquals("s-1", id)
    val request = stub.requests.single()
    assertEquals("POST", request.method())
    assertEquals("/v3/session/", request.uri().path)
  }

  @Test
  fun nullableSettersTakeNull() {
    val params =
      TransactionsCreateParams.builder()
        .transactionId("t-1")
        .transactionAt(OffsetDateTime.parse("2026-11-01T10:00:00Z"))
        .transactionCategory(TransactionsCreateParams.TransactionCategory.FINANCE)
        .subject(
          TransactionsCreateParams.Subject.builder()
            .vendorData("user-42")
            .fullName("Ana Ruiz")
            .build(),
        )
        .transactionDetails(
          TransactionsCreateParams.TransactionDetails.builder()
            .direction(TransactionsCreateParams.TransactionDetails.Direction.INBOUND)
            .amount(10.0)
            .currency("EUR")
            .amountInDefaultCurrency(null) // @Nullable in Java: Kotlin accepts null
            .build(),
        )
        .includeCryptoScreening(null)
        .build()
    assertTrue(params._json().get("include_crypto_screening").isNull)
    assertNull(params.includeCryptoScreening().getOrNull())
    assertFailsWith<IllegalStateException> { TransactionsCreateParams.builder().build() }
  }

  @Test
  fun pagesAreIterable() {
    val stub =
      Stub(
        200 to
          """{"count":2,"next":"https://verification.didit.me/v3/sessions/?offset=1","previous":null,
            "results":[{"session_id":"a","session_kind":"user","session_number":1,"status":"Approved",
            "created_at":"2026-11-01T10:00:00Z"}]}""",
        200 to
          """{"count":2,"next":null,"previous":null,"results":[{"session_id":"b","session_kind":"business",
            "session_number":2,"status":"In Review","created_at":"2026-11-01T10:00:00Z"}]}""",
      )
    val ids = client(stub).sessions().list().map(SessionsListResponse.Results::sessionId)
    assertEquals(listOf("a", "b"), ids)
    assertEquals(2, stub.requests.size)
  }

  @Test
  fun errorsAreTyped() {
    val stub = Stub(404 to """{"detail":"Not found."}""")
    val error =
      assertFailsWith<NotFoundException> { client(stub).sessions().retrieveDecision("missing") }
    assertEquals(404, error.statusCode())
    assertEquals("Not found.", error.body().getOrNull()?.get("detail")?.asText())
  }

  @Test
  fun webhooksWithWhen() {
    val secret = "whsec_test_kotlin_0123456789"
    val now = System.currentTimeMillis() / 1000
    val body =
      """{"session_id":"s-1","status":"Approved","timestamp":$now,"webhook_type":"status.updated"}"""
    val signature = hmac(secret, Webhooks.canonicalJson(body))
    val headers = mapOf("X-Signature-V2" to signature, "X-Timestamp" to now.toString())
    assertEquals("session s-1 is Approved", handleWebhook(body.toByteArray(), headers, secret))
    assertIs<SessionStatusUpdatedEvent>(Webhooks.verify(body, headers, secret))
  }

  private fun hmac(secret: String, message: String): String {
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
    return mac.doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) }
  }
}
