package me.didit.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads response examples from the OpenAPI documents in {@code openapi/}, the same way the code
 * generator does, so example values never need to be copied into the repository.
 */
public final class SpecExamples {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Map<String, JsonNode> DOCS = new ConcurrentHashMap<>();

  private SpecExamples() {}

  public static Path repoRoot() {
    String root = System.getProperty("didit.repoRoot");
    return root == null ? Path.of("").toAbsolutePath() : Path.of(root);
  }

  public static JsonNode document(String file) {
    return DOCS.computeIfAbsent(
        file,
        f -> {
          try {
            return MAPPER.readTree(repoRoot().resolve("openapi").resolve(f).toFile());
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
  }

  /** The example of the {@code status} JSON response of {@code method path}. */
  public static JsonNode response(String file, String method, String path, String status) {
    JsonNode doc = document(file);
    JsonNode op = doc.path("paths").path(path).path(method.toLowerCase(java.util.Locale.ROOT));
    JsonNode response = deref(doc, op.path("responses").path(status));
    JsonNode media = response.path("content").path("application/json");
    if (media.has("example")) {
      return media.get("example");
    }
    JsonNode examples = media.path("examples");
    if (examples.isObject() && examples.size() > 0) {
      Iterator<JsonNode> values = examples.elements();
      JsonNode first = deref(doc, values.next());
      if (first.has("value")) {
        return first.get("value");
      }
    }
    JsonNode schema = deref(doc, media.path("schema"));
    if (schema.has("example")) {
      return schema.get("example");
    }
    throw new AssertionError("No example for " + method + " " + path + " " + status);
  }

  static JsonNode deref(JsonNode doc, JsonNode node) {
    JsonNode current = node;
    for (int i = 0; i < 20 && current.has("$ref"); i++) {
      String ref = current.get("$ref").asText();
      current = doc.at(ref.substring(1));
    }
    return current;
  }
}
