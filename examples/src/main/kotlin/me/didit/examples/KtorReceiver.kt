package me.didit.examples

import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import me.didit.errors.WebhookVerificationException
import me.didit.webhooks.VerifyOptions
import me.didit.webhooks.Webhooks

/** A Ktor webhook receiver. */
fun main() {
  val options = VerifyOptions.of(System.getenv("DIDIT_WEBHOOK_SECRET"))
  embeddedServer(Netty, port = 8080) {
      routing {
        post("/webhooks/didit") {
          val body = call.receive<ByteArray>()
          try {
            val event = Webhooks.verify(body, { name -> call.request.header(name) }, options)
            println(describe(event))
            call.respond(HttpStatusCode.OK)
          } catch (e: WebhookVerificationException) {
            call.respond(HttpStatusCode.Unauthorized, e.reason().code())
          }
        }
      }
    }
    .start(wait = true)
}
