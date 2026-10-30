package me.didit.core;

import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;

/**
 * Base class of generated responses whose body shape depends on the HTTP status, for example 200
 * with a result and 202 with a background job.
 */
public abstract class MultiStatusResponse {
  private final int statusCode;

  /** The response body, owned by this instance. */
  protected final JsonNode json;

  /**
   * Wraps a response body the subclass owns.
   *
   * @param statusCode the HTTP status
   * @param json the body; not copied
   */
  protected MultiStatusResponse(int statusCode, JsonNode json) {
    this.statusCode = statusCode;
    this.json = json;
  }

  /**
   * The HTTP status the API answered with.
   *
   * @return the status
   */
  public final int statusCode() {
    return statusCode;
  }

  /**
   * A copy of the response body.
   *
   * @return a deep copy
   */
  public final JsonNode _json() {
    return json.deepCopy();
  }

  // Two generated types with the same JSON are still different values.
  @SuppressWarnings("EqualsGetClass")
  @Override
  public final boolean equals(@Nullable Object other) {
    if (other == null || other.getClass() != getClass()) {
      return false;
    }
    MultiStatusResponse that = (MultiStatusResponse) other;
    return statusCode == that.statusCode && json.equals(that.json);
  }

  @Override
  public final int hashCode() {
    return json.hashCode() * 31 + statusCode;
  }

  @Override
  public String toString() {
    String name = getClass().getName();
    return name.substring(name.lastIndexOf('.') + 1)
        + "{status="
        + statusCode
        + ", body="
        + Json.redacted(json)
        + "}";
  }
}
