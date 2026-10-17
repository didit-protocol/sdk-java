package me.didit.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.didit.errors.UnexpectedResponseException;
import me.didit.testing.TestJson;
import org.junit.jupiter.api.Test;

final class ModelTest {
  @Test
  void buildersMakeImmutableValues() {
    Pet.Builder builder = Pet.builder().name("Rex").age(3).nickname(null).kind(Pet.Kind.DOG);
    Pet pet = builder.build();
    builder.name("Changed");
    assertEquals("Rex", pet.name());
    assertTrue(pet.nickname().isEmpty());
    assertTrue(pet._json().has("nickname"), "explicit null is kept");
    assertTrue(pet._json().get("nickname").isNull());
    assertEquals(Pet.Kind.DOG, pet.kind().orElseThrow());
    Pet copy = pet.toBuilder().age(4).build();
    assertEquals(3L, pet.age().orElseThrow());
    assertEquals(4L, copy.age().orElseThrow());
    assertNotEquals(pet, copy);
    assertEquals(pet, pet.toBuilder().build());
    assertEquals(pet.hashCode(), pet.toBuilder().build().hashCode());
    assertFalse(pet.equals(null));
    assertFalse(pet.equals("Rex"));
    // _json returns a copy.
    pet._json().put("name", "Mutated");
    assertEquals("Rex", pet.name());
  }

  @Test
  void requiredFields() {
    IllegalStateException e = assertThrows(IllegalStateException.class, () -> Pet.builder().build());
    assertTrue(e.getMessage().contains("name"));
    assertThrows(IllegalStateException.class, () -> Pet.builder().name("Rex").buildWithPhoto());
    assertThrows(NullPointerException.class, () -> Pet.builder().name(null));
    UnexpectedResponseException missing =
        assertThrows(UnexpectedResponseException.class, () -> Pet.fromJson(TestJson.parse("{}")).name());
    assertTrue(missing.getMessage().contains("'name' is missing"));
    UnexpectedResponseException nul =
        assertThrows(UnexpectedResponseException.class, () -> Pet.fromJson(TestJson.parse("{\"name\":null}")).name());
    assertTrue(nul.getMessage().contains("is null"));
    UnexpectedResponseException wrong =
        assertThrows(UnexpectedResponseException.class, () -> Pet.fromJson(TestJson.parse("{\"age\":\"old\"}")).age());
    assertTrue(wrong.getMessage().contains("Field 'age'"));
    assertThrows(UnexpectedResponseException.class, () -> Pet.fromJson(TestJson.parse("[]")));
  }

  @Test
  void unknownFieldsArePreserved() {
    Pet pet = Pet.fromJson(TestJson.parse("{\"name\":\"Rex\",\"color\":\"brown\",\"extra\":1}"));
    assertEquals(Map.of("color", TestJson.parse("\"brown\"")), pet._additionalProperties());
    Pet withMore = pet.toBuilder().putAdditionalProperty("weight", 12.5).remove("color").build();
    assertEquals(12.5, withMore._json().get("weight").asDouble());
    assertFalse(withMore._json().has("color"));
  }

  @Test
  void toStringRedactsSecrets() {
    Pet pet = Pet.builder().name("Rex").apiToken("secret-value").extra(Map.of("password", "p", "list", List.of(Map.of("session_token", "t")))).build();
    String text = pet.toString();
    assertTrue(text.startsWith("Pet{"), text);
    assertFalse(text.contains("secret-value"));
    assertFalse(text.contains("\"p\""));
    assertFalse(text.contains("\"t\""));
    assertTrue(text.contains("[REDACTED]"));
    Pet withFile = Pet.builder().name("Rex").photo(FileUpload.of(new byte[] {1}, "a.png")).build();
    assertTrue(withFile.toString().contains("files=[photo]"));
  }

  @Test
  void files() throws Exception {
    Path tmp = Files.createTempFile("didit", ".png");
    try {
      Files.write(tmp, new byte[] {9, 8});
      Pet pet = Pet.builder().name("Rex").photo(tmp).photos(List.of(FileUpload.of(new byte[] {1}, "b.webp"))).build();
      FileUpload photo = pet.photo().orElseThrow();
      assertEquals(tmp.getFileName().toString(), photo.fileName());
      assertEquals("image/png", photo.contentType());
      assertEquals(2, photo.read().length);
      assertEquals("image/webp", pet.photos().orElseThrow().get(0).contentType());
      assertEquals(pet, pet.toBuilder().build());
    } finally {
      Files.delete(tmp);
    }
    FileUpload stream = FileUpload.of(new ByteArrayInputStream(new byte[] {1, 2, 3}), "doc.pdf", null);
    assertEquals(3, stream.read().length);
    assertEquals(3, stream.read().length, "a stream is read once and reused for retries");
    assertEquals("application/pdf", stream.contentType());
    assertEquals("text/plain", stream.withContentType("text/plain").contentType());
    assertEquals("x.csv", stream.withFileName("x.csv").fileName());
    assertEquals("text/csv", FileUpload.of(new byte[0], "a.csv").contentType());
    assertEquals("image/jpeg", FileUpload.of(new byte[0], "a.JPEG").contentType());
    assertEquals("image/heic", FileUpload.of(new byte[0], "a.heic").contentType());
    assertEquals("application/octet-stream", FileUpload.of(new byte[0], "blob").contentType());
    assertEquals("image/gif", FileUpload.of(new byte[0], "a.gif").contentType());
    assertTrue(FileUpload.of(new byte[0], "a.bin", "x/y").toString().contains("x/y"));
    assertThrows(UncheckedIOException.class, () -> FileUpload.of(Path.of("does-not-exist.jpg")).read());
    assertEquals("file", FileUpload.of(Path.of("/")).fileName());
    Pet empty = Pet.builder().name("a").build();
    assertTrue(empty.photo().isEmpty());
    assertTrue(empty.photos().isEmpty());
  }

  @Test
  void openEnums() {
    assertSame(Pet.Kind.CAT, Pet.Kind.of("cat"));
    Pet.Kind fish = Pet.Kind.of("fish");
    assertFalse(fish.isKnown());
    assertTrue(Pet.Kind.CAT.isKnown());
    assertEquals("fish", fish.value());
    assertEquals("fish", fish.toString());
    assertEquals(fish, Pet.Kind.of("fish"));
    assertEquals(fish.hashCode(), Pet.Kind.of("fish").hashCode());
    assertNotEquals(fish, Pet.Kind.CAT);
    assertFalse(fish.equals(null));
    assertEquals(fish, Pet.fromJson(TestJson.parse("{\"kind\":\"fish\"}")).kind().orElseThrow());
  }

  @Test
  void unions() {
    Pet.PetOrName a = Pet.PetOrName.fromJson(TestJson.parse("{\"name\":\"Rex\",\"session_token\":\"s\"}"));
    assertTrue(a.isPet());
    assertEquals(a, Pet.PetOrName.fromJson(TestJson.parse("{\"name\":\"Rex\",\"session_token\":\"s\"}")));
    assertEquals(a.hashCode(), Pet.PetOrName.fromJson(a._json()).hashCode());
    assertFalse(a.equals(null));
    assertFalse(a.toString().contains("\"s\""));
    Pet.PetOrName b = Pet.PetOrName.fromJson(TestJson.parse("\"Rex\""));
    assertFalse(b.isPet());
    assertNotEquals(a, b);
  }

  @Test
  void converters() {
    assertEquals("1", Conv.STRING.read(TestJson.parse("1")));
    assertEquals("true", Conv.STRING.read(TestJson.parse("true")));
    assertThrows(UnexpectedResponseException.class, () -> Conv.STRING.read(TestJson.parse("{}")));
    assertEquals(5L, Conv.LONG.read(TestJson.parse("\"5\"")));
    assertEquals(5L, Conv.LONG.read(TestJson.parse("5.0")));
    assertThrows(UnexpectedResponseException.class, () -> Conv.LONG.read(TestJson.parse("5.5")));
    assertThrows(UnexpectedResponseException.class, () -> Conv.LONG.read(TestJson.parse("99999999999999999999")));
    assertThrows(UnexpectedResponseException.class, () -> Conv.LONG.read(TestJson.parse("\"x\"")));
    assertThrows(UnexpectedResponseException.class, () -> Conv.LONG.read(TestJson.parse("[]")));
    assertEquals(1.25, Conv.DOUBLE.read(TestJson.parse("\"1.25\"")));
    assertThrows(IllegalArgumentException.class, () -> Conv.DOUBLE.write(Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> Conv.DOUBLE.write(Double.POSITIVE_INFINITY));
    assertEquals(2.5, Conv.DOUBLE.write(2.5).asDouble());
    assertTrue(Conv.BOOLEAN.read(TestJson.parse("\"true\"")));
    assertThrows(UnexpectedResponseException.class, () -> Conv.BOOLEAN.read(TestJson.parse("\"yes\"")));
    assertEquals(OffsetDateTime.of(2026, 11, 1, 10, 0, 0, 0, ZoneOffset.UTC), Conv.DATE_TIME.read(TestJson.parse("\"2026-11-01T10:00:00Z\"")));
    assertEquals(OffsetDateTime.of(2026, 11, 1, 10, 0, 0, 0, ZoneOffset.ofHours(2)), Conv.DATE_TIME.read(TestJson.parse("\"2026-11-01T10:00:00+02:00\"")));
    assertEquals(OffsetDateTime.of(2026, 11, 1, 10, 0, 0, 0, ZoneOffset.UTC), Conv.DATE_TIME.read(TestJson.parse("\"2026-11-01T10:00:00\"")));
    assertEquals(OffsetDateTime.of(2026, 11, 1, 10, 0, 0, 0, ZoneOffset.UTC), Conv.DATE_TIME.read(TestJson.parse("\"2026-11-01 10:00:00\"")));
    assertEquals(OffsetDateTime.of(2026, 11, 1, 0, 0, 0, 0, ZoneOffset.UTC), Conv.DATE_TIME.read(TestJson.parse("\"2026-11-01\"")));
    assertEquals(OffsetDateTime.of(1970, 1, 1, 0, 0, 10, 0, ZoneOffset.UTC), Conv.DATE_TIME.read(TestJson.parse("10")));
    assertThrows(UnexpectedResponseException.class, () -> Conv.DATE_TIME.read(TestJson.parse("\"tomorrow\"")));
    assertThrows(UnexpectedResponseException.class, () -> Conv.DATE_TIME.read(TestJson.parse("true")));
    assertEquals("2026-11-01T10:15:30Z", Conv.DATE_TIME.write(OffsetDateTime.of(2026, 11, 1, 10, 15, 30, 0, ZoneOffset.UTC)).asText());
    assertEquals("2026-11-01T10:00:00Z", Conv.DATE_TIME.write(OffsetDateTime.of(2026, 11, 1, 10, 0, 0, 0, ZoneOffset.UTC)).asText());
    assertEquals(LocalDate.of(2026, 11, 1), Conv.DATE.read(TestJson.parse("\"2026-11-01T23:00:00Z\"")));
    assertThrows(UnexpectedResponseException.class, () -> Conv.DATE.read(TestJson.parse("\"11/01/2026\"")));
    assertThrows(UnexpectedResponseException.class, () -> Conv.DATE.read(TestJson.parse("1")));
    assertEquals("2026-11-01", Conv.DATE.write(LocalDate.of(2026, 11, 1)).asText());
    assertEquals(TestJson.parse("{\"a\":[1]}"), Conv.ANY.write(TestJson.parse("{\"a\":[1]}")));
    Map<String, JsonNode> object = Conv.OBJECT.read(TestJson.parse("{\"a\":1,\"b\":\"x\"}"));
    assertEquals(1, object.get("a").asInt());
    assertThrows(UnexpectedResponseException.class, () -> Conv.OBJECT.read(TestJson.parse("[]")));
    assertEquals(2, Conv.OBJECT.write(object).size());
    UnexpectedResponseException item =
        assertThrows(UnexpectedResponseException.class, () -> Conv.list(Conv.LONG).read(TestJson.parse("[1,\"x\"]")));
    assertTrue(item.getMessage().contains("item 1"));
    assertThrows(UnexpectedResponseException.class, () -> Conv.list(Conv.LONG).read(TestJson.parse("[1,null]")));
    assertThrows(UnexpectedResponseException.class, () -> Conv.list(Conv.LONG).read(TestJson.parse("{}")));
    assertEquals(Map.of("a", 1L), Conv.map(Conv.LONG).read(TestJson.parse("{\"a\":1,\"b\":null}")));
    assertThrows(UnexpectedResponseException.class, () -> Conv.map(Conv.LONG).read(TestJson.parse("{\"a\":\"x\"}")));
    assertThrows(UnexpectedResponseException.class, () -> Conv.map(Conv.LONG).read(TestJson.parse("[]")));
    assertEquals("{\"a\":1}", Conv.map(Conv.LONG).write(Map.of("a", 1L)).toString());
    assertThrows(UnexpectedResponseException.class, () -> Conv.model(Pet::fromJson).read(TestJson.parse("1")));
    assertEquals("Rex", Conv.model(Pet::fromJson).write(Pet.builder().name("Rex").build()).get("name").asText());
    assertEquals("\"x\"", Conv.union(Pet.PetOrName::fromJson).write(Pet.PetOrName.fromJson(TestJson.parse("\"x\""))).toString());
    assertThrows(UnexpectedResponseException.class, () -> Conv.enumeration(Pet.Kind::of).read(TestJson.parse("1")));
    assertEquals("\"cat\"", Conv.enumeration(Pet.Kind::of).write(Pet.Kind.CAT).toString());
  }

  @Test
  void jsonHelpers() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("model", Pet.builder().name("Rex").build());
    map.put("union", Pet.PetOrName.fromJson(TestJson.parse("\"x\"")));
    map.put("enum", Pet.Kind.DOG);
    map.put("when", OffsetDateTime.of(2026, 1, 2, 3, 4, 0, 0, ZoneOffset.UTC));
    map.put("day", LocalDate.of(2026, 1, 2));
    map.put("time", java.time.LocalTime.of(3, 4));
    map.put("optional", Optional.of(1));
    map.put("empty", Optional.empty());
    map.put("list", List.of(1, "a"));
    map.put("big", new BigInteger("123456789012345678901234567890"));
    map.put("null", null);
    map.put("status", Pet.Outcome.fromResponse(200, TestJson.parse("{\"ok\":true}")));
    JsonNode node = Json.toNode(map);
    assertEquals(
        "{\"model\":{\"name\":\"Rex\"},\"union\":\"x\",\"enum\":\"dog\",\"when\":\"2026-01-02T03:04:00Z\","
            + "\"day\":\"2026-01-02\",\"time\":\"03:04\",\"optional\":1,\"empty\":null,\"list\":[1,\"a\"],"
            + "\"big\":123456789012345678901234567890,\"null\":null,\"status\":{\"ok\":true}}",
        Json.write(node));
    assertThrows(UnexpectedResponseException.class, () -> Json.parse("{"));
    assertThrows(UnexpectedResponseException.class, () -> Json.parse(new byte[] {'['}));
    assertEquals("12345678901234567890.10", Json.parse("12345678901234567890.10").decimalValue().toPlainString());
    assertTrue(Json.hasValue(TestJson.parse("{\"t\":\"a\"}"), "t", "a"));
    assertFalse(Json.hasValue(TestJson.parse("[1]"), "t", "a"));
    assertTrue(Json.hasFields(TestJson.parse("{\"a\":1,\"b\":2}"), "a", "b"));
    assertFalse(Json.hasFields(TestJson.parse("{\"a\":1}"), "a", "b"));
    assertFalse(Json.hasFields(TestJson.parse("1"), "a"));
    assertTrue(Json.hasAnyField(TestJson.parse("{\"a\":1}"), "x", "a"));
    assertFalse(Json.hasAnyField(TestJson.parse("{\"a\":1}"), "x"));
    assertFalse(Json.hasAnyField(TestJson.parse("1"), "x"));
    assertEquals(2, Json.nodes().arrayNode().add(1).add(2).size());
  }

  @Test
  void headers() {
    Map<String, List<String>> raw = new LinkedHashMap<>();
    raw.put(null, List.of("HTTP/1.1 200"));
    raw.put("x-request-id", List.of(""));
    raw.put("Request-Id", List.of("r-2"));
    raw.put("x-api-key", List.of("k"));
    raw.put("Set-Cookie", List.of("c"));
    raw.put("X-Signature", List.of("s"));
    raw.put("Content-Type", List.of("application/json"));
    Headers h = Headers.of(raw);
    assertEquals("r-2", h.requestId().orElseThrow());
    assertEquals("application/json", h.firstValue("content-type").orElseThrow());
    assertEquals(List.of(), h.allValues("missing"));
    assertTrue(h.firstValue("missing").isEmpty());
    String text = h.toString();
    assertFalse(text.contains("=k"));
    assertFalse(text.contains("=c"));
    assertFalse(text.contains("=s,") || text.endsWith("=s}"));
    assertTrue(text.contains("application/json"));
    assertEquals(h, Headers.of(raw));
    assertEquals(h.hashCode(), Headers.of(raw).hashCode());
    assertFalse(h.equals("x"));
    assertTrue(Headers.empty().requestId().isEmpty());
    assertEquals(7, h.map().size() + 1);
    assertTrue(MockTransportHeaders.amzn().requestId().isPresent());
  }

  static final class MockTransportHeaders {
    static Headers amzn() {
      return Headers.of(Map.of("X-Amzn-Requestid", List.of("aws-1")));
    }
  }
}
