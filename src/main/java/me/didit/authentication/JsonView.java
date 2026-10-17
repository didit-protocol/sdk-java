package me.didit.authentication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import me.didit.core.Conv;
import me.didit.core.Json;

/** A read-only typed view of a JSON object from the Authentication runtime. */
abstract class JsonView {
  final ObjectNode json;

  JsonView(ObjectNode json) {
    this.json = json;
  }

  final <T> Optional<T> optional(String field, Conv<T> conv) {
    return Json.optional(json, field, conv);
  }

  /**
   * Any field, including ones this SDK version does not declare.
   *
   * @param name the JSON field name
   * @return the value, if present and not null
   */
  public final Optional<JsonNode> field(String name) {
    return optional(name, Conv.ANY);
  }

  /**
   * A copy of the whole JSON object.
   *
   * @return the JSON
   */
  public final ObjectNode _json() {
    return json.deepCopy();
  }

  /** Field names whose values stay out of {@code toString()}, at any depth. */
  private static final Set<String> PERSONAL =
      Set.of(
          "code",
          "nonce",
          "email",
          "phone",
          "phone_number",
          "birthdate",
          "nationality",
          "vendor_data",
          "verified",
          "verified_identity");

  @Override
  public String toString() {
    StringBuilder out = new StringBuilder(getClass().getSimpleName()).append('{');
    boolean first = true;
    for (Map.Entry<String, JsonNode> e : json.properties()) {
      if (!first) {
        out.append(", ");
      }
      first = false;
      out.append(e.getKey()).append('=').append(redacted(e.getKey(), e.getValue()));
    }
    return out.append('}').toString();
  }

  private static JsonNode redacted(String key, JsonNode value) {
    if (value.isNull()) {
      return value;
    }
    if (PERSONAL.contains(key) || key.contains("token") || key.contains("name")) {
      return Json.nodes().textNode("[REDACTED]");
    }
    if (value.isObject()) {
      ObjectNode out = Json.nodes().objectNode();
      for (Map.Entry<String, JsonNode> e : value.properties()) {
        out.set(e.getKey(), redacted(e.getKey(), e.getValue()));
      }
      return out;
    }
    if (value.isArray()) {
      ArrayNode out = Json.nodes().arrayNode();
      for (JsonNode item : value) {
        out.add(redacted("", item));
      }
      return out;
    }
    return value;
  }
}
