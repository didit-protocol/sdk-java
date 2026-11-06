package me.didit.core;

import org.jspecify.annotations.Nullable;

/**
 * Turns a successful HTTP response into the value an operation returns. Generated resources pick
 * one from {@link Responses}.
 *
 * @param <T> the value type
 */
public interface ResponseHandler<T extends @Nullable Object> {
  /**
   * Parses the response.
   *
   * @param response the successful response; the handler owns its body
   * @return the value
   */
  T handle(SuccessResponse response);

  /**
   * Whether the body is streamed to the caller instead of being read into memory.
   *
   * @return true for binary responses
   */
  default boolean streaming() {
    return false;
  }
}
