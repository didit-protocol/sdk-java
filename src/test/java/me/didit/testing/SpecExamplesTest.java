package me.didit.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpecExamplesTest {
  private static final String DOCUMENT =
      "{\"paths\": {\"/v3/live/\": {\"get\": {\"responses\": {\"200\": {\"content\":"
          + " {\"application/json\": {\"example\": {\"live\": true}}}}}}}},"
          + " \"components\": {\"schemas\": {\"Shared\": {\"example\": {\"from\": \"document\"}}}}}";

  // A removed operation as the spec sync stores it: its schemas travel with it, and a name the
  // document still has keeps the document's schema, as in the generator.
  private static final String REGISTRY =
      "{\"version\": 1, \"methods\": {"
          + "\"gone.create\": {\"spec\": \"verification\", \"http_method\": \"POST\","
          + " \"path\": \"/v3/gone/\", \"operation\": {\"responses\": {\"201\": {\"content\":"
          + " {\"application/json\": {\"schema\": {\"$ref\": \"#/components/schemas/Gone\"}}}}}},"
          + " \"components\": {\"Gone\": {\"example\": {\"id\": \"g_1\"}}}},"
          + "\"gone.list\": {\"spec\": \"verification\", \"http_method\": \"GET\","
          + " \"path\": \"/v3/gone/\", \"operation\": {\"responses\": {\"200\": {\"content\":"
          + " {\"application/json\": {\"schema\": {\"$ref\": \"#/components/schemas/Shared\"}}}}}},"
          + " \"components\": {\"Shared\": {\"example\": {\"from\": \"registry\"}}}}}}";

  private static Path write(Path root, String file, String json) throws IOException {
    Files.createDirectories(root.resolve("openapi"));
    return Files.write(
        root.resolve("openapi").resolve(file), json.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void readsALiveOperationFromTheDocument(@TempDir Path root) throws IOException {
    write(root, "verification.json", DOCUMENT);
    assertEquals(
        "{\"live\":true}",
        SpecExamples.response(root, "verification.json", "GET", "/v3/live/", "200").toString());
  }

  @Test
  void readsARemovedOperationFromTheDeprecationRegistry(@TempDir Path root) throws IOException {
    write(root, "verification.json", DOCUMENT);
    write(root, "deprecated-operations.json", REGISTRY);
    assertEquals(
        "{\"id\":\"g_1\"}",
        SpecExamples.response(root, "verification.json", "POST", "/v3/gone/", "201").toString());
    assertEquals(
        "{\"from\":\"document\"}",
        SpecExamples.response(root, "verification.json", "GET", "/v3/gone/", "200").toString());
  }

  @Test
  void aRegistryEntryOfAnotherDocumentIsNotUsed(@TempDir Path root) throws IOException {
    write(root, "account.json", DOCUMENT);
    write(root, "deprecated-operations.json", REGISTRY);
    assertThrows(
        AssertionError.class,
        () -> SpecExamples.response(root, "account.json", "POST", "/v3/gone/", "201"));
  }

  @Test
  void withoutARegistryAMissingOperationHasNoExample(@TempDir Path root) throws IOException {
    write(root, "verification.json", DOCUMENT);
    assertThrows(
        AssertionError.class,
        () -> SpecExamples.response(root, "verification.json", "POST", "/v3/gone/", "201"));
  }
}
