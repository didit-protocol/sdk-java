package me.didit.core;

import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;

/**
 * Base class of the generated union ({@code oneOf}) types: an immutable JSON value that matches
 * one of several shapes. Generated subclasses offer {@code isX()} and {@code asX()} per variant.
 */
public abstract class JsonUnion {
  /** The wrapped value, owned by this instance. */
  protected final JsonNode json;

  /**
   * Wraps a value the subclass owns.
   *
   * @param json the value; not copied
   */
  protected JsonUnion(JsonNode json) {
    this.json = json;
  }

  /**
   * A copy of the underlying JSON value.
   *
   * @return a deep copy
   */
  public final JsonNode _json() {
    return json.deepCopy();
  }

  @Override
  public final boolean equals(@Nullable Object other) {
    return other != null && other.getClass() == getClass() && json.equals(((JsonUnion) other).json);
  }

  @Override
  public final int hashCode() {
    return json.hashCode() * 31 + getClass().hashCode();
  }

  @Override
  public String toString() {
    String name = getClass().getName();
    return name.substring(name.lastIndexOf('.') + 1).replace('$', '.') + Json.redacted(json);
  }
}
