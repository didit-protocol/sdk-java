package me.didit.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.function.BiFunction;
import me.didit.errors.UnexpectedResponseException;
import org.jspecify.annotations.Nullable;

/** The response handlers the generated resources use. */
public final class Responses {
  private Responses() {}

  /**
   * A JSON body read with {@code conv}.
   *
   * @param <T> the body type
   * @param conv the converter
   * @return the handler
   */
  public static <T> ResponseHandler<T> json(Conv<T> conv) {
    return response -> {
      JsonNode json = response.json();
      if (json.isNull()) {
        throw new UnexpectedResponseException(
            response.request.describe() + " returned an empty body");
      }
      return conv.read(json);
    };
  }

  /**
   * A JSON body the spec does not describe.
   *
   * @return the handler
   */
  public static ResponseHandler<JsonNode> jsonNode() {
    return SuccessResponse::json;
  }

  /**
   * No body.
   *
   * @return the handler
   */
  public static ResponseHandler<@Nullable Void> none() {
    return response -> null;
  }

  /**
   * A binary body, streamed.
   *
   * @return the handler
   */
  public static ResponseHandler<BinaryResponse> binary() {
    return new ResponseHandler<>() {
      @Override
      public BinaryResponse handle(SuccessResponse response) {
        return new BinaryResponse(response.headers(), response.stream());
      }

      @Override
      public boolean streaming() {
        return true;
      }
    };
  }

  /**
   * A body whose shape depends on the status.
   *
   * @param <T> the response type
   * @param fromResponse its {@code fromResponse(status, json)} method
   * @return the handler
   */
  public static <T> ResponseHandler<T> byStatus(BiFunction<Integer, JsonNode, T> fromResponse) {
    return response -> fromResponse.apply(response.statusCode(), response.json());
  }

  /**
   * A list page with {@code count}, {@code next}, {@code previous} and {@code results}; the next
   * page is the {@code next} link.
   *
   * @param <T> the item type
   * @param item the item converter
   * @return the handler
   */
  public static <T> ResponseHandler<Page<T>> page(Conv<T> item) {
    return response -> Page.first(response, item, Page.Style.NEXT_LINK);
  }

  /**
   * A list page with {@code count}, {@code next_offset} and {@code results}; the next page is the
   * same request at {@code next_offset}.
   *
   * @param <T> the item type
   * @param item the item converter
   * @return the handler
   */
  public static <T> ResponseHandler<Page<T>> offsetPage(Conv<T> item) {
    return response -> Page.first(response, item, Page.Style.NEXT_OFFSET);
  }
}
