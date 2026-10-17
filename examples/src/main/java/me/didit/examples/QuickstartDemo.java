package me.didit.examples;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import me.didit.Didit;
import me.didit.webhooks.Webhooks;

/** Runs the quickstart against {@link MockDiditServer}, with a webhook signed like Didit does. */
public final class QuickstartDemo {
  private QuickstartDemo() {}

  public static void main(String[] args) throws Exception {
    try (MockDiditServer server = new MockDiditServer()) {
      Didit client =
          Didit.builder().apiKey("synthetic-demo-key").baseUrl(server.baseUrl()).build();
      Quickstart.run(client, "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");

      String secret = "whsec_test_demo_0123456789";
      long now = Instant.now().getEpochSecond();
      String body =
          "{\"created_at\":" + now + ",\"event_id\":\"7c9e6679-7425-40de-944b-e07fc1f90ae7\","
              + "\"session_id\":\"11111111-2222-4333-8444-555555555555\",\"status\":\"Approved\","
              + "\"timestamp\":" + now + ",\"webhook_type\":\"status.updated\"}";
      Map<String, String> headers = new LinkedHashMap<>();
      headers.put("X-Signature-V2", hmac(secret, Webhooks.canonicalJson(body)));
      headers.put("X-Timestamp", Long.toString(now));
      Quickstart.onWebhook(body.getBytes(StandardCharsets.UTF_8), headers, secret);
      System.out.println("Mock API saw: " + server.log());
    }
  }

  static String hmac(String secret, String message)
      throws NoSuchAlgorithmException, InvalidKeyException {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    StringBuilder hex = new StringBuilder();
    for (byte b : mac.doFinal(message.getBytes(StandardCharsets.UTF_8))) {
      hex.append(String.format("%02x", b));
    }
    return hex.toString();
  }
}
