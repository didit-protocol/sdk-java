package me.didit.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Base class of every generated object type: an immutable, typed view of a JSON object.
 *
 * <p>Fields the SDK does not know yet are kept and readable through {@link
 * #_additionalProperties()}; {@link #_json()} returns the whole object, which also tells an absent
 * field from a {@code null} one.
 */
public abstract class JsonModel {
  /** The underlying object, owned by this instance; generated getters read it. */
  protected final ObjectNode json;
  private final Map<String, List<FileUpload>> files;

  /**
   * Wraps a JSON object the subclass owns.
   *
   * @param json the object; not copied, so the caller must not keep a reference
   * @param files files to upload with a multipart request, by field name
   */
  protected JsonModel(ObjectNode json, Map<String, List<FileUpload>> files) {
    this.json = json;
    this.files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
  }

  /**
   * The JSON field names this type declares.
   *
   * @return the declared field names
   */
  protected abstract Set<String> knownFields();

  /**
   * A copy of the underlying JSON object, including unknown fields.
   *
   * @return a deep copy
   */
  public final ObjectNode _json() {
    return json.deepCopy();
  }

  /**
   * The fields of the JSON object this SDK version does not declare.
   *
   * @return unknown fields by name, in their original order
   */
  public final Map<String, JsonNode> _additionalProperties() {
    Map<String, JsonNode> out = new LinkedHashMap<>();
    Set<String> known = knownFields();
    for (Map.Entry<String, JsonNode> entry : json.properties()) {
      if (!known.contains(entry.getKey())) {
        out.put(entry.getKey(), entry.getValue().deepCopy());
      }
    }
    return Collections.unmodifiableMap(out);
  }

  /**
   * The files to upload, by field name. Empty except for multipart request parameters.
   *
   * @return the files
   */
  public final Map<String, List<FileUpload>> _files() {
    return files;
  }

  /** Reads one file field, for generated getters. */
  protected final Optional<FileUpload> file(String field) {
    List<FileUpload> list = files.get(field);
    return list == null || list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  /** Reads a list-of-files field, for generated getters. */
  protected final Optional<List<FileUpload>> fileList(String field) {
    return Optional.ofNullable(files.get(field));
  }

  // Two generated types with the same JSON are still different values.
  @SuppressWarnings("EqualsGetClass")
  @Override
  public final boolean equals(@Nullable Object other) {
    if (this == other) {
      return true;
    }
    if (other == null || other.getClass() != getClass()) {
      return false;
    }
    JsonModel that = (JsonModel) other;
    return json.equals(that.json) && files.equals(that.files);
  }

  @Override
  public final int hashCode() {
    return Objects.hash(getClass(), json, files);
  }

  /** The class name and the JSON, with the values of secret-looking fields redacted. */
  @Override
  public String toString() {
    String name = getClass().getName();
    name = name.substring(name.lastIndexOf('.') + 1).replace('$', '.');
    String text = name + Json.redacted(json);
    if (!files.isEmpty()) {
      text += " files=" + files.keySet();
    }
    return text;
  }

  /**
   * Base class of the generated builders.
   *
   * @param <B> the concrete builder
   */
  public abstract static class BaseBuilder<B extends BaseBuilder<B>> {
    private final ObjectNode json;
    private final Map<String, List<FileUpload>> files;

    /** Starts from an empty object. */
    protected BaseBuilder() {
      this.json = Json.MAPPER.createObjectNode();
      this.files = new LinkedHashMap<>();
    }

    /**
     * Starts from a copy of {@code from}.
     *
     * @param from the value to copy
     */
    protected BaseBuilder(JsonModel from) {
      this.json = from.json.deepCopy();
      this.files = new LinkedHashMap<>(from.files);
    }

    /**
     * This builder, typed.
     *
     * @return this
     */
    protected abstract B self();

    /** Sets a field; {@code value} must not be null. */
    protected final <T> B set(String field, Conv<T> conv, T value) {
      Objects.requireNonNull(value, field);
      json.set(field, conv.write(value));
      return self();
    }

    /** Sets a nullable field; {@code null} sends JSON null. */
    protected final <T> B setNullable(String field, Conv<T> conv, @Nullable T value) {
      json.set(field, value == null ? NullNode.getInstance() : conv.write(value));
      return self();
    }

    /** Sets a field of any JSON type from a Java value. */
    protected final B setAny(String field, @Nullable Object value) {
      json.set(field, Json.toNode(value));
      return self();
    }

    /** Sets a file field. */
    protected final B setFile(String field, FileUpload file) {
      Objects.requireNonNull(file, field);
      files.put(field, List.of(file));
      return self();
    }

    /** Sets a list-of-files field. */
    protected final B setFiles(String field, List<FileUpload> list) {
      files.put(field, List.copyOf(list));
      return self();
    }

    /**
     * Sets a field this SDK version does not declare. The value is converted like {@link
     * Json#toNode(Object)}.
     *
     * @param field the JSON field name
     * @param value the value; {@code null} sends JSON null
     * @return this builder
     */
    public final B putAdditionalProperty(String field, @Nullable Object value) {
      json.set(field, Json.toNode(value));
      return self();
    }

    /**
     * Removes a field, declared or not, so that it is not sent.
     *
     * @param field the JSON field name
     * @return this builder
     */
    public final B remove(String field) {
      json.remove(field);
      files.remove(field);
      return self();
    }

    /** Fails when a required field is missing (or null, unless the field is nullable). */
    protected final void require(String field, boolean nullable) {
      JsonNode value = json.get(field);
      if (value == null || (!nullable && value.isNull())) {
        throw new IllegalStateException("Missing required field '" + field + "'");
      }
    }

    /** Fails when a required file field is missing. */
    protected final void requireFile(String field) {
      List<FileUpload> list = files.get(field);
      if (list == null || list.isEmpty()) {
        throw new IllegalStateException("Missing required file '" + field + "'");
      }
    }

    /** A copy of the JSON built so far. */
    protected final ObjectNode buildJson() {
      return json.deepCopy();
    }

    /** A copy of the files set so far. */
    protected final Map<String, List<FileUpload>> buildFiles() {
      Map<String, List<FileUpload>> out = new LinkedHashMap<>();
      for (Map.Entry<String, List<FileUpload>> entry : files.entrySet()) {
        out.put(entry.getKey(), new ArrayList<>(entry.getValue()));
      }
      return out;
    }
  }
}
