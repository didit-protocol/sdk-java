package me.didit.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import me.didit.errors.UnexpectedResponseException;
import me.didit.testing.MockTransport;
import me.didit.testing.RecordedRequest;
import org.junit.jupiter.api.Test;

final class PageTest {
  private final MockTransport transport = new MockTransport();

  private Page<Pet> first(String body) {
    transport.respond(200, body);
    ApiClient client =
        new ApiClient(
            ClientOptions.builder()
                .apiKey("k")
                .environment(n -> null)
                .transport(transport)
                .build());
    return client
        .execute(
            ApiRequest.builder("GET", "/v3/pets/")
                .params(Pet.builder().name("ignored").build())
                .queryFields("name")
                .build(),
            RequestOptions.none(),
            Responses.page(Conv.model(Pet::fromJson)))
        .data();
  }

  private static String page(String next, String... names) {
    String results =
        java.util.Arrays.stream(names)
            .map(n -> "{\"name\":\"" + n + "\"}")
            .collect(Collectors.joining(","));
    return "{\"count\":3,\"next\":"
        + next
        + ",\"previous\":null,\"results\":["
        + results
        + "],\"total\":9}";
  }

  @Test
  void iteratesEveryPage() {
    Page<Pet> page =
        first(page("\"https://verification.didit.me/v3/pets/?limit=2&offset=2\"", "a", "b"));
    transport.respond(200, page("null", "c"));
    assertEquals(2, page.results().size());
    assertEquals(3L, page.count().orElseThrow());
    assertTrue(page.previous().isEmpty());
    assertTrue(page.hasNextPage());
    assertEquals(9, page._json().get("total").asInt());
    List<String> names = new ArrayList<>();
    for (Pet pet : page) {
      names.add(pet.name());
    }
    assertEquals(List.of("a", "b", "c"), names);
    RecordedRequest second = transport.requests().get(1);
    assertEquals("/v3/pets/", second.path());
    assertEquals("2", second.query().get("offset").get(0));
    assertEquals("k", second.header("x-api-key"));
    assertTrue(page.toString().contains("results=2"));
  }

  @Test
  void streamsAndAsync() throws Exception {
    Page<Pet> page = first(page("\"/v3/pets/?offset=2\"", "a", "b"));
    transport.respond(200, page("null", "c"));
    assertEquals(
        List.of("a", "b", "c"),
        page.autoPager().stream().map(Pet::name).collect(Collectors.toList()));
    transport.respond(200, page("null", "c"));
    List<String> names = new ArrayList<>();
    page.forEachAsync(p -> names.add(p.name())).get();
    assertEquals(List.of("a", "b", "c"), names);
  }

  @Test
  void lastPage() {
    Page<Pet> page = first(page("null", "a"));
    assertFalse(page.hasNextPage());
    assertThrows(NoSuchElementException.class, page::nextPage);
    assertFalse(first(page("\"\"", "a")).hasNextPage());
    java.util.Iterator<Pet> it = page.iterator();
    it.next();
    assertFalse(it.hasNext());
    assertThrows(NoSuchElementException.class, it::next);
    assertTrue(first("{\"results\":null}").results().isEmpty());
  }

  @Test
  void refusesLinksToOtherOriginsAndLoops() {
    Page<Pet> evil = first(page("\"https://evil.example.com/v3/pets/?offset=2\"", "a"));
    UnexpectedResponseException e = assertThrows(UnexpectedResponseException.class, evil::nextPage);
    assertTrue(e.getMessage().contains("evil.example.com"));
    assertEquals(1, transport.requests().size(), "nothing is sent to another origin");

    Page<Pet> loop = first(page("\"https://verification.didit.me/v3/pets/?name=ignored\"", "a"));
    assertThrows(UnexpectedResponseException.class, loop::nextPage);

    Page<Pet> twoStep = first(page("\"/v3/pets/?offset=2\"", "a"));
    transport.respond(200, page("\"/v3/pets/?offset=2\"", "b"));
    Page<Pet> second = twoStep.nextPage();
    assertThrows(UnexpectedResponseException.class, second::nextPage);

    Page<Pet> badUrl = first(page("\"http://[bad\"", "a"));
    assertThrows(UnexpectedResponseException.class, badUrl::nextPage);
  }

  @Test
  void pagesThatAreNotObjects() throws Exception {
    assertThrows(UnexpectedResponseException.class, () -> first("[1]"));
    Page<Pet> page = first(page("\"/v3/pets/?offset=1\"", "a"));
    transport.respond(200, "[]");
    ExecutionException e = assertThrows(ExecutionException.class, () -> page.nextPageAsync().get());
    assertTrue(e.getCause() instanceof UnexpectedResponseException);
  }
}
