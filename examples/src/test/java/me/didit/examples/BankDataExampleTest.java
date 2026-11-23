package me.didit.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import me.didit.Didit;
import me.didit.core.Headers;
import me.didit.core.TransportResponse;
import me.didit.webhooks.Webhooks;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class BankDataExampleTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void readsAccountsOnlyForTheCreatedLink(boolean matches) throws IOException {
    Path fixtures = Path.of(System.getProperty("didit.repoRoot"), "openapi");
    ObjectMapper mapper = new ObjectMapper();
    JsonNode paths = mapper.readTree(fixtures.resolve("verification.json").toFile()).get("paths");
    JsonNode link = paths.at("/~1v3~1bank~1links~1{id}~1/get/responses/200/content/application~1json/examples/Completed/value");
    JsonNode accounts = paths.at("/~1v3~1bank~1connections~1{id}~1accounts~1/get/responses/200/content/application~1json/examples/Page/value");
    JsonNode vectors = mapper.readTree(fixtures.resolve("webhook-vectors.json").toFile()).get("vectors");
    JsonNode created = java.util.stream.StreamSupport.stream(vectors.spliterator(), false)
        .filter(v -> v.get("name").asText().equals("bank_connection_created")).findFirst().orElseThrow();
    ObjectNode event = (ObjectNode) mapper.readTree(created.get("body").asText());
    if (matches) {
      event.set("connection_id", link.get("connection_id"));
    }

    List<String> requests = new ArrayList<>();
    Didit client = Didit.builder().apiKey("test-key").environment(name -> null)
        .transport(request -> {
          requests.add(request.uri().getPath());
          JsonNode response = requests.size() == 1 ? link : accounts;
          return CompletableFuture.completedFuture(new TransportResponse(200, Headers.empty(),
              response.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }).build();
    String id = link.get("id").asText();
    assertEquals(matches, BankDataExample.readMatchingAccounts(client, id, Webhooks.parseEvent(event)));
    List<String> expected = new ArrayList<>(List.of("/v3/bank/links/" + id + "/"));
    if (matches) {
      expected.add("/v3/bank/connections/" + link.get("connection_id").asText() + "/accounts/");
    }
    assertEquals(expected, requests);
  }
}
