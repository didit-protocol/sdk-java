package me.didit.examples

import me.didit.Didit
import me.didit.types.SessionsCreateParams
import me.didit.webhooks.SessionStatusUpdatedEvent
import me.didit.webhooks.TransactionStatusUpdatedEvent
import me.didit.webhooks.UnknownEvent
import me.didit.webhooks.WebhookEvent
import me.didit.webhooks.Webhooks
import kotlin.jvm.optionals.getOrNull

/** The quickstart in Kotlin: nullable types where the API allows null, plain types elsewhere. */
fun createSession(client: Didit, workflowId: String): String {
  val session =
    client
      .sessions()
      .create(
        SessionsCreateParams.builder()
          .workflowId(workflowId)
          .vendorData("user-42")
          .build(),
      )
  val id: String = session.sessionId() // required in the API: never null
  val vendorData: String? = session.vendorData().getOrNull() // optional in the API
  println("Session $id for ${vendorData ?: "anonymous"}: ${session.url()}")
  return id
}

fun describe(event: WebhookEvent): String =
  when (event) {
    is SessionStatusUpdatedEvent -> "session ${event.sessionId().getOrNull()} is ${event.status().getOrNull()}"
    is TransactionStatusUpdatedEvent -> "transaction ${event.transactionId().getOrNull()} is ${event.status().getOrNull()}"
    is UnknownEvent -> "unknown event ${event.webhookType()}"
    else -> event.webhookType()
  }

fun handleWebhook(body: ByteArray, headers: Map<String, String>, secret: String): String =
  describe(Webhooks.verify(body, headers, secret))
