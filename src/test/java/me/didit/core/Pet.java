package me.didit.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** A hand-written model shaped like the generated ones, for core tests. */
final class Pet extends JsonModel {
  private static final Set<String> KNOWN_FIELDS =
      Set.of("name", "nickname", "age", "born_at", "tags", "kind", "photo", "api_token", "extra");

  private Pet(ObjectNode json, Map<String, List<FileUpload>> files) {
    super(json, files);
  }

  static Pet fromJson(JsonNode json) {
    return new Pet(Json.copyObject(json), Map.of());
  }

  static Builder builder() {
    return new Builder();
  }

  Builder toBuilder() {
    return new Builder(this);
  }

  String name() {
    return Json.required(json, "name", Conv.STRING);
  }

  Optional<String> nickname() {
    return Json.optional(json, "nickname", Conv.STRING);
  }

  Optional<Long> age() {
    return Json.optional(json, "age", Conv.LONG);
  }

  Optional<OffsetDateTime> bornAt() {
    return Json.optional(json, "born_at", Conv.DATE_TIME);
  }

  Optional<List<String>> tags() {
    return Json.optional(json, "tags", Conv.list(Conv.STRING));
  }

  Optional<Kind> kind() {
    return Json.optional(json, "kind", Conv.enumeration(Kind::of));
  }

  Optional<FileUpload> photo() {
    return file("photo");
  }

  Optional<List<FileUpload>> photos() {
    return fileList("photos");
  }

  @Override
  protected Set<String> knownFields() {
    return KNOWN_FIELDS;
  }

  static final class Builder extends JsonModel.BaseBuilder<Builder> {
    private Builder() {}

    private Builder(Pet from) {
      super(from);
    }

    Builder name(String name) {
      return set("name", Conv.STRING, name);
    }

    Builder nickname(@Nullable String nickname) {
      return setNullable("nickname", Conv.STRING, nickname);
    }

    Builder age(long age) {
      return set("age", Conv.LONG, age);
    }

    Builder bornAt(OffsetDateTime bornAt) {
      return set("born_at", Conv.DATE_TIME, bornAt);
    }

    Builder tags(List<String> tags) {
      return set("tags", Conv.list(Conv.STRING), tags);
    }

    Builder kind(Kind kind) {
      return set("kind", Conv.enumeration(Kind::of), kind);
    }

    Builder extra(@Nullable Object extra) {
      return setAny("extra", extra);
    }

    Builder apiToken(String token) {
      return set("api_token", Conv.STRING, token);
    }

    Builder photo(FileUpload photo) {
      return setFile("photo", photo);
    }

    Builder photo(Path photo) {
      return setFile("photo", FileUpload.of(photo));
    }

    Builder photos(List<FileUpload> photos) {
      return setFiles("photos", photos);
    }

    Pet build() {
      require("name", false);
      return new Pet(buildJson(), buildFiles());
    }

    Pet buildWithPhoto() {
      requireFile("photo");
      return build();
    }

    @Override
    protected Builder self() {
      return this;
    }
  }

  static final class Kind extends StringEnum {
    static final Kind CAT = new Kind("cat");
    static final Kind DOG = new Kind("dog");
    private static final List<Kind> KNOWN_VALUES = List.of(CAT, DOG);

    private Kind(String value) {
      super(value);
    }

    static Kind of(String value) {
      return StringEnum.lookup(KNOWN_VALUES, value, Kind::new);
    }

    @Override
    public boolean isKnown() {
      return KNOWN_VALUES.contains(this);
    }
  }

  /** A union: a pet or a plain name. */
  static final class PetOrName extends JsonUnion {
    private PetOrName(JsonNode json) {
      super(json);
    }

    static PetOrName fromJson(JsonNode json) {
      return new PetOrName(json.deepCopy());
    }

    boolean isPet() {
      return json.isObject();
    }
  }

  /** A status union. */
  static final class Outcome extends MultiStatusResponse {
    private Outcome(int status, JsonNode json) {
      super(status, json);
    }

    static Outcome fromResponse(int status, JsonNode json) {
      return new Outcome(status, json.deepCopy());
    }
  }
}
