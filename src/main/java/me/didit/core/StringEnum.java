package me.didit.core;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Base class of the generated open enums: a string value with constants for the values this SDK
 * version knows. Values from a newer API are kept, never rejected.
 */
public abstract class StringEnum {
  private final String value;

  /**
   * Creates the value.
   *
   * @param value the string sent on the wire
   */
  protected StringEnum(String value) {
    this.value = Objects.requireNonNull(value, "value");
  }

  /**
   * Returns the known constant equal to {@code value}, or a new instance.
   *
   * @param <T> the enum type
   * @param known the known constants
   * @param value the string value
   * @param create creates an instance for an unknown value
   * @return the enum value
   */
  protected static <T extends StringEnum> T lookup(
      List<T> known, String value, Function<String, T> create) {
    for (T constant : known) {
      if (constant.value().equals(value)) {
        return constant;
      }
    }
    return create.apply(value);
  }

  /**
   * The string sent on the wire.
   *
   * @return the value
   */
  public final String value() {
    return value;
  }

  /**
   * Whether this SDK version declares the value.
   *
   * @return true for a known value
   */
  public abstract boolean isKnown();

  // Two generated types with the same JSON are still different values.
  @SuppressWarnings("EqualsGetClass")
  @Override
  public final boolean equals(@Nullable Object other) {
    return other != null
        && other.getClass() == getClass()
        && value.equals(((StringEnum) other).value);
  }

  @Override
  public final int hashCode() {
    return value.hashCode();
  }

  @Override
  public final String toString() {
    return value;
  }
}
