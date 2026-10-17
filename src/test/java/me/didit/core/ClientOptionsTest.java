package me.didit.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import me.didit.errors.ConfigurationException;
import org.junit.jupiter.api.Test;

final class ClientOptionsTest {
  @Test
  void defaults() {
    ClientOptions o = ClientOptions.builder().environment(name -> null).build();
    assertTrue(o.apiKey().isEmpty());
    assertTrue(o.accountToken().isEmpty());
    assertEquals("https://verification.didit.me", o.baseUrl().toString());
    assertEquals("https://apx.didit.me/auth/v2", o.accountBaseUrl().toString());
    assertEquals(Duration.ofSeconds(60), o.timeout());
    assertEquals(2, o.maxRetries());
    assertTrue(o.organizationId().isEmpty());
    assertTrue(o.applicationId().isEmpty());
    assertFalse(o.debugLogging());
    assertTrue(o.transport() instanceof JdkHttpTransport);
    assertTrue(o.defaultHeaders().isEmpty());
  }

  @Test
  void environmentFallbacks() {
    Map<String, String> env =
        Map.of(
            "DIDIT_API_KEY", " key-from-env ",
            "DIDIT_ORGANIZATION_ID", "org-env",
            "DIDIT_APPLICATION_ID", "app-env",
            "DIDIT_LOG", "DEBUG",
            "DIDIT_ACCOUNT_TOKEN", "never-read");
    ClientOptions o = ClientOptions.builder().environment(env::get).build();
    assertEquals("key-from-env", o.apiKey().orElseThrow());
    assertEquals("org-env", o.organizationId().orElseThrow());
    assertEquals("app-env", o.applicationId().orElseThrow());
    assertTrue(o.debugLogging());
    assertTrue(o.accountToken().isEmpty(), "the account token has no environment fallback");
    ClientOptions explicit =
        ClientOptions.builder()
            .environment(env::get)
            .apiKey("explicit")
            .organizationId("org")
            .applicationId("app")
            .debugLogging(false)
            .build();
    assertEquals("explicit", explicit.apiKey().orElseThrow());
    assertEquals("org", explicit.organizationId().orElseThrow());
    assertFalse(explicit.debugLogging());
    ClientOptions blank = ClientOptions.builder().environment(name -> "  ").apiKey(" ").build();
    assertTrue(blank.apiKey().isEmpty());
  }

  @Test
  void baseUrlRules() {
    assertEquals(
        "http://localhost:4010",
        ClientOptions.builder()
            .environment(n -> null)
            .baseUrl("http://localhost:4010/")
            .build()
            .baseUrl()
            .toString());
    assertEquals(
        "http://127.0.0.1:4010/prefix",
        ClientOptions.builder()
            .environment(n -> null)
            .baseUrl("http://127.0.0.1:4010/prefix")
            .build()
            .baseUrl()
            .toString());
    assertEquals(
        "http://[::1]:4010",
        ClientOptions.builder()
            .environment(n -> null)
            .accountBaseUrl("http://[::1]:4010")
            .build()
            .accountBaseUrl()
            .toString());
    for (String bad :
        new String[] {
          "http://verification.didit.me",
          "ftp://verification.didit.me",
          "verification.didit.me",
          "https://verification.didit.me?x=1",
          "https://verification.didit.me#x",
          "https://exa mple.com",
          "http://10.0.0.1"
        }) {
      assertThrows(
          ConfigurationException.class,
          () -> ClientOptions.builder().environment(n -> null).baseUrl(bad).build(),
          bad);
    }
  }

  @Test
  void validation() {
    assertThrows(IllegalArgumentException.class, () -> ClientOptions.builder().maxRetries(-1));
    assertThrows(
        IllegalArgumentException.class, () -> ClientOptions.builder().timeout(Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> ClientOptions.builder().timeout(Duration.ofSeconds(-1)));
    assertThrows(IllegalArgumentException.class, () -> RequestOptions.builder().maxRetries(-1));
    assertThrows(
        IllegalArgumentException.class, () -> RequestOptions.builder().timeout(Duration.ZERO));
    HttpClient redirecting =
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    assertThrows(
        IllegalArgumentException.class,
        () -> ClientOptions.builder().httpClient(redirecting).build());
  }

  @Test
  void toBuilderKeepsEverythingAndToStringRedacts() {
    HttpTransport transport = r -> new java.util.concurrent.CompletableFuture<>();
    ClientOptions o =
        ClientOptions.builder()
            .environment(n -> null)
            .apiKey("secret-key")
            .accountToken("secret-token")
            .organizationId("org")
            .applicationId("app")
            .timeout(Duration.ofSeconds(5))
            .maxRetries(4)
            .defaultHeader("X-A", "1")
            .transport(transport)
            .debugLogging(true)
            .build();
    ClientOptions copy = o.toBuilder().build();
    assertEquals("secret-key", copy.apiKey().orElseThrow());
    assertEquals("secret-token", copy.accountToken().orElseThrow());
    assertEquals(Duration.ofSeconds(5), copy.timeout());
    assertEquals(4, copy.maxRetries());
    assertEquals(Map.of("X-A", "1"), copy.defaultHeaders());
    assertSame(transport, copy.transport());
    assertTrue(copy.debugLogging());
    assertFalse(o.toString().contains("secret-key"));
    assertFalse(o.toString().contains("secret-token"));
    assertTrue(o.toString().contains("[REDACTED]"));
    assertTrue(
        ClientOptions.builder().environment(n -> null).build().toString().contains("apiKey=unset"));
    HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    assertTrue(
        ClientOptions.builder().environment(n -> null).httpClient(client).build().transport()
            instanceof JdkHttpTransport);
    assertTrue(
        ClientOptions.builder()
                .environment(n -> null)
                .timeout(Duration.ofSeconds(5))
                .build()
                .transport()
            instanceof JdkHttpTransport);
    RequestOptions ro = RequestOptions.builder().apiKey("hidden").header("X", "1").build();
    assertFalse(ro.toString().contains("hidden"));
    assertTrue(RequestOptions.none().headers().isEmpty());
    assertTrue(RequestOptions.none().queryParams().isEmpty());
    assertTrue(RequestOptions.none().idempotencyKey().isEmpty());
  }

  @Test
  void loopbackDetection() {
    assertTrue(ClientOptions.isLoopback("localhost"));
    assertTrue(ClientOptions.isLoopback("127.0.0.1"));
    assertTrue(ClientOptions.isLoopback("[::1]"));
    assertFalse(ClientOptions.isLoopback("example.com"));
    assertFalse(ClientOptions.isLoopback("10.1.1.1"));
    assertFalse(ClientOptions.isLoopback("999.1.1.1"));
  }
}
