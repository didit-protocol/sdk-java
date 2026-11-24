package me.didit.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Reads response examples from the OpenAPI documents in {@code openapi/}, the same way the code
 * generator does, so example values never need to be copied into the repository. An operation the
 * document no longer has is read from {@code openapi/deprecated-operations.json}, which the
 * generator merges back in for the deprecated methods it still generates (DESIGN section 13).
 */
public final class SpecExamples {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String DEPRECATED = "deprecated-operations.json";
  private static final Map<String, JsonNode> DOCS = new ConcurrentHashMap<>();

  private SpecExamples() {}

  public static Path repoRoot() {
    String root = System.getProperty("didit.repoRoot");
    return root == null ? Path.of("").toAbsolutePath() : Path.of(root);
  }

  public static JsonNode document(String file) {
    return document(repoRoot(), file);
  }

  static JsonNode document(Path root, String file) {
    Path location = root.resolve("openapi").resolve(file).toAbsolutePath().normalize();
    return DOCS.computeIfAbsent(
        location.toString(),
        f -> {
          try {
            return MAPPER.readTree(location.toFile());
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
  }

  /** The example of the {@code status} JSON response of {@code method path}. */
  public static JsonNode response(String file, String method, String path, String status) {
    return response(repoRoot(), file, method, path, status);
  }

  static JsonNode response(Path root, String file, String method, String path, String status) {
    JsonNode doc = document(root, file);
    String httpMethod = method.toLowerCase(java.util.Locale.ROOT);
    JsonNode op = doc.path("paths").path(path).path(httpMethod);
    if (op.isMissingNode()) {
      JsonNode entry = deprecatedEntry(root, file, httpMethod, path);
      if (entry != null) {
        doc = withComponents(doc, entry.path("components"));
        op = entry.path("operation");
      }
    }
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

  /** The registry entry of a removed operation of {@code file}, or null. */
  private static @Nullable JsonNode deprecatedEntry(
      Path root, String file, String httpMethod, String path) {
    if (!Files.exists(root.resolve("openapi").resolve(DEPRECATED))) {
      return null;
    }
    Iterator<JsonNode> entries = document(root, DEPRECATED).path("methods").elements();
    while (entries.hasNext()) {
      JsonNode entry = entries.next();
      String spec = entry.path("spec").asText("verification");
      if (file.equals(spec + ".json")
          && httpMethod.equalsIgnoreCase(entry.path("http_method").asText())
          && path.equals(entry.path("path").asText())) {
        return entry;
      }
    }
    return null;
  }

  /** {@code doc} with the entry's schemas added where it has none of that name, as the generator does. */
  private static JsonNode withComponents(JsonNode doc, JsonNode components) {
    ObjectNode merged = (ObjectNode) doc.deepCopy();
    JsonNode componentsNode = merged.path("components");
    ObjectNode allComponents =
        componentsNode.isObject() ? (ObjectNode) componentsNode : merged.putObject("components");
    JsonNode schemasNode = allComponents.path("schemas");
    ObjectNode schemas =
        schemasNode.isObject() ? (ObjectNode) schemasNode : allComponents.putObject("schemas");
    Iterator<Map.Entry<String, JsonNode>> fields = components.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      if (!schemas.has(field.getKey())) {
        schemas.set(field.getKey(), field.getValue());
      }
    }
    return merged;
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
