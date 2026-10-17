package me.didit.authentication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
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

  @Override
  public String toString() {
    StringBuilder out = new StringBuilder(getClass().getSimpleName()).append('{');
    boolean first = true;
    for (java.util.Map.Entry<String, JsonNode> e : json.properties()) {
      if (!first) {
        out.append(", ");
      }
      first = false;
      String key = e.getKey();
      boolean secret =
          key.contains("token")
              || key.equals("code")
              || key.equals("nonce")
              || key.equals("email")
              || key.equals("phone")
              || key.equals("phone_number")
              || key.equals("name");
      out.append(key).append('=').append(secret ? "[REDACTED]" : e.getValue().toString());
    }
    return out.append('}').toString();
  }
}
