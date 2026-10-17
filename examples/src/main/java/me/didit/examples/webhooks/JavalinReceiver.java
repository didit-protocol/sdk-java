package me.didit.examples.webhooks;

import io.javalin.Javalin;
import me.didit.errors.WebhookVerificationException;
import me.didit.webhooks.VerifyOptions;
import me.didit.webhooks.WebhookEvent;
import me.didit.webhooks.Webhooks;

/** A Javalin webhook receiver. */
public final class JavalinReceiver {
  private JavalinReceiver() {}

  public static void main(String[] args) {
    VerifyOptions options = VerifyOptions.of(System.getenv("DIDIT_WEBHOOK_SECRET"));
    Javalin.create(
            config ->
                config.routes.post(
                    "/webhooks/didit",
                    ctx -> {
                      try {
                        WebhookEvent event =
                            Webhooks.verify(ctx.bodyAsBytes(), ctx::header, options);
                        System.out.println("Received " + event.webhookType());
                        ctx.status(200);
                      } catch (WebhookVerificationException e) {
                        ctx.status(401);
                      }
                    }))
        .start(8080);
  }
}
