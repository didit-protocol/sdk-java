package me.didit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import me.didit.core.ClientOptions;
import me.didit.errors.ConfigurationException;
import me.didit.testing.MockTransport;
import me.didit.testing.RecordedRequest;
import org.junit.jupiter.api.Test;

final class DiditTest {
  @Test
  void builderSetsEveryOption() {
    MockTransport transport = new MockTransport();
    Didit client =
        Didit.builder()
            .apiKey("k")
            .accountToken("t")
            .organizationId("o")
            .applicationId("a")
            .baseUrl("http://localhost:4010")
            .accountBaseUrl("http://localhost:4011/auth/v2")
            .timeout(Duration.ofSeconds(7))
            .maxRetries(1)
            .defaultHeader("X-A", "1")
            .debugLogging(false)
            .environment(name -> null)
            .transport(transport)
            .build();
    ClientOptions o = client.options();
    assertEquals("k", o.apiKey().orElseThrow());
    assertEquals("t", o.accountToken().orElseThrow());
    assertEquals("o", o.organizationId().orElseThrow());
    assertEquals("a", o.applicationId().orElseThrow());
    assertEquals("http://localhost:4010", o.baseUrl().toString());
    assertEquals("http://localhost:4011/auth/v2", o.accountBaseUrl().toString());
    assertEquals(Duration.ofSeconds(7), o.timeout());
    assertEquals(1, o.maxRetries());
    assertEquals(Map.of("X-A", "1"), o.defaultHeaders());
    assertSame(transport, o.transport());
    assertFalse(client.toString().contains("k,"));
    assertTrue(client.toString().contains("[REDACTED]"));
    Didit other = Didit.builder().httpClient(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()).build();
    assertTrue(other.options().transport() instanceof me.didit.core.JdkHttpTransport);
  }

  @Test
  void withOptionsDerivesAClientThatSharesTheTransport() {
    MockTransport transport = new MockTransport();
    Didit client = transport.client();
    Didit slow = client.withOptions(o -> o.timeout(Duration.ofMinutes(2)).maxRetries(5));
    assertEquals(Duration.ofMinutes(2), slow.options().timeout());
    assertEquals(5, slow.options().maxRetries());
    assertEquals(Duration.ofSeconds(60), client.options().timeout());
    assertSame(client.options().transport(), slow.options().transport());
    assertEquals("test-api-key", slow.options().apiKey().orElseThrow());
    transport.respond(200, "{\"count\":0,\"next\":null,\"previous\":null,\"results\":[]}");
    slow.workflows().list();
    RecordedRequest r = transport.only();
    assertEquals(Duration.ofMinutes(2), r.raw().timeout());
  }

  @Test
  void constructors() {
    Didit fromKey = new Didit("k");
    assertEquals("k", fromKey.options().apiKey().orElseThrow());
    Didit fromOptions = new Didit(ClientOptions.builder().apiKey("x").environment(n -> null).build());
    assertEquals("x", fromOptions.options().apiKey().orElseThrow());
    // The no-argument constructor reads the environment; it never fails for a missing key.
    Didit fromEnv = new Didit();
    assertTrue(fromEnv.options().baseUrl().toString().startsWith("https://"));
    assertThrows(ConfigurationException.class, () -> Didit.builder().baseUrl("http://example.com").build());
  }

  @Test
  void applicationScopedCallsNeedBothIds() {
    MockTransport transport = new MockTransport();
    Didit client = Didit.builder().apiKey("k").environment(n -> null).transport(transport).build();
    ConfigurationException e = assertThrows(ConfigurationException.class, () -> client.cases().list());
    assertTrue(e.getMessage().contains("DIDIT_ORGANIZATION_ID"));
    assertEquals(0, transport.requests().size());
  }
}
