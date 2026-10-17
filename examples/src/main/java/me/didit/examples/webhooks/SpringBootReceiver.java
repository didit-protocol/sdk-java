package me.didit.examples.webhooks;

import me.didit.errors.WebhookVerificationException;
import me.didit.webhooks.SessionStatusUpdatedEvent;
import me.didit.webhooks.TransactionStatusUpdatedEvent;
import me.didit.webhooks.WebhookEvent;
import me.didit.webhooks.Webhooks;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * A Spring Boot webhook receiver. Take the body as {@code byte[]} so the signature covers the
 * exact bytes Didit sent.
 */
@SpringBootApplication
@RestController
public class SpringBootReceiver {
  private final String secret = System.getenv("DIDIT_WEBHOOK_SECRET");

  @PostMapping("/webhooks/didit")
  public ResponseEntity<Void> receive(@RequestBody byte[] body, @RequestHeader HttpHeaders headers) {
    WebhookEvent event;
    try {
      event = Webhooks.verify(body, headers::getFirst, secret);
    } catch (WebhookVerificationException e) {
      return ResponseEntity.status(401).build();
    }
    if (event instanceof SessionStatusUpdatedEvent) {
      SessionStatusUpdatedEvent session = (SessionStatusUpdatedEvent) event;
      System.out.println(session.sessionId().orElse("?") + " is " + session.status().orElse("?"));
    } else if (event instanceof TransactionStatusUpdatedEvent) {
      System.out.println("Transaction " + ((TransactionStatusUpdatedEvent) event).transactionId().orElse("?"));
    }
    return ResponseEntity.ok().build();
  }

  public static void main(String[] args) {
    SpringApplication.run(SpringBootReceiver.class, args);
  }
}
