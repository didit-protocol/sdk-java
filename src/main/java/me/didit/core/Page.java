package me.didit.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import me.didit.errors.UnexpectedResponseException;

/**
 * One page of a list (DESIGN 8): {@code count}, {@code next}, {@code previous} and {@code
 * results}. Iterating a page walks every following page too:
 *
 * <pre>{@code
 * for (WorkflowListItem workflow : client.workflows().list()) {
 *   System.out.println(workflow.uuid());
 * }
 * }</pre>
 *
 * @param <T> the item type
 */
public final class Page<T> implements Iterable<T> {
  enum Style {
    NEXT_LINK,
    NEXT_OFFSET
  }

  private final ApiClient client;
  private final ApiRequest request;
  private final RequestOptions options;
  private final ObjectNode json;
  private final Conv<T> item;
  private final Style style;
  private final Set<String> seen;
  private final List<T> results;

  private Page(
      ApiClient client,
      ApiRequest request,
      RequestOptions options,
      ObjectNode json,
      Conv<T> item,
      Style style,
      Set<String> seen) {
    this.client = client;
    this.request = request;
    this.options = options;
    this.json = json;
    this.item = item;
    this.style = style;
    this.seen = seen;
    JsonNode results = json.get("results");
    this.results =
        results == null || results.isNull() ? List.of() : Conv.list(item).read(results);
  }

  static <T> Page<T> first(SuccessResponse response, Conv<T> item, Style style) {
    JsonNode json = response.json();
    if (!json.isObject()) {
      throw new UnexpectedResponseException(
          response.request.describe() + " returned " + Json.kind(json) + " instead of a page");
    }
    Set<String> seen = new HashSet<>();
    seen.add(key(response.uri));
    return new Page<>(
        response.client,
        response.request,
        response.options,
        (ObjectNode) json,
        item,
        style,
        seen);
  }

  private static String key(URI uri) {
    String query = uri.getRawQuery();
    return uri.getRawPath() + (query == null ? "" : "?" + query);
  }

  /**
   * The items of this page only.
   *
   * @return the items
   */
  public List<T> results() {
    return results;
  }

  /**
   * The total number of items across all pages, when the API reports it.
   *
   * @return the count
   */
  public Optional<Long> count() {
    return Json.optional(json, "count", Conv.LONG);
  }

  /**
   * The link to the next page.
   *
   * @return the URL, if there is a next page
   */
  public Optional<String> next() {
    return Json.optional(json, "next", Conv.STRING);
  }

  /**
   * The link to the previous page.
   *
   * @return the URL, if there is a previous page
   */
  public Optional<String> previous() {
    return Json.optional(json, "previous", Conv.STRING);
  }

  /**
   * The offset of the next page, for lists that report {@code next_offset}.
   *
   * @return the offset, if there is a next page
   */
  public Optional<Long> nextOffset() {
    return Json.optional(json, "next_offset", Conv.LONG);
  }

  /**
   * The whole page body, including fields other than the standard ones (for example {@code
   * total_transactions}).
   *
   * @return a copy of the JSON object
   */
  public ObjectNode _json() {
    return json.deepCopy();
  }

  /**
   * Whether another page follows.
   *
   * @return true when {@link #nextPage()} returns a page
   */
  public boolean hasNextPage() {
    if (style == Style.NEXT_OFFSET) {
      return nextOffset().isPresent() && !results.isEmpty();
    }
    return next().filter(s -> !s.isEmpty()).isPresent();
  }

  /**
   * Fetches the next page.
   *
   * @return the next page
   * @throws NoSuchElementException when there is no next page
   * @throws UnexpectedResponseException when the next link points to another origin or back to a
   *     page already fetched
   */
  public Page<T> nextPage() {
    return ApiClient.await(nextPageAsync());
  }

  /**
   * Fetches the next page.
   *
   * @return a future completed with the next page
   */
  public CompletableFuture<Page<T>> nextPageAsync() {
    if (!hasNextPage()) {
      return CompletableFuture.failedFuture(new NoSuchElementException("No next page"));
    }
    ApiClient.PageTarget target;
    String key;
    try {
      if (style == Style.NEXT_OFFSET) {
        long offset = nextOffset().orElseThrow();
        target = ApiClient.PageTarget.offset(offset);
        key = "offset=" + offset;
      } else {
        URI link = new URI(next().orElseThrow());
        URI resolved = ApiClient.Paths.sameOrigin(client.baseFor(request), link);
        target = ApiClient.PageTarget.uri(resolved);
        key = key(resolved);
      }
    } catch (URISyntaxException e) {
      return CompletableFuture.failedFuture(
          new UnexpectedResponseException("The next page link is not a valid URL", e));
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
    if (seen.contains(key)) {
      return CompletableFuture.failedFuture(
          new UnexpectedResponseException(
              "The next page link points back to a page already fetched: " + key));
    }
    Set<String> nextSeen = new HashSet<>(seen);
    nextSeen.add(key);
    ResponseHandler<Page<T>> handler =
        response -> {
          JsonNode body = response.json();
          if (!body.isObject()) {
            throw new UnexpectedResponseException("The next page is not a JSON object");
          }
          return new Page<>(client, request, options, (ObjectNode) body, item, style, nextSeen);
        };
    return client.send(request, options, handler, target).thenApply(RawResponse::data);
  }

  /**
   * Iterates every item of this page and the following ones, fetching pages as needed.
   *
   * @return an iterator over all items
   */
  @Override
  public Iterator<T> iterator() {
    return autoPager().iterator();
  }

  /**
   * Every item of this page and the following ones.
   *
   * @return an iterable that fetches pages lazily
   */
  public AutoPager<T> autoPager() {
    return new AutoPager<>(this);
  }

  /**
   * Calls {@code action} for every item of this page and the following ones, fetching pages
   * asynchronously.
   *
   * @param action the action
   * @return a future completed when the last page was processed
   */
  public CompletableFuture<Void> forEachAsync(Consumer<? super T> action) {
    results.forEach(action);
    if (!hasNextPage()) {
      return CompletableFuture.completedFuture(null);
    }
    return nextPageAsync().thenCompose(page -> page.forEachAsync(action));
  }

  @Override
  public String toString() {
    return "Page{results=" + results.size() + ", count=" + count().orElse(null) + "}";
  }

  /**
   * Every item across pages, as an {@link Iterable} or a {@link Stream}.
   *
   * @param <T> the item type
   */
  public static final class AutoPager<T> implements Iterable<T> {
    private final Page<T> first;

    AutoPager(Page<T> first) {
      this.first = first;
    }

    @Override
    public Iterator<T> iterator() {
      return new Iterator<>() {
        private Page<T> page = first;
        private Iterator<T> items = first.results().iterator();

        @Override
        public boolean hasNext() {
          while (!items.hasNext()) {
            if (!page.hasNextPage()) {
              return false;
            }
            page = page.nextPage();
            items = page.results().iterator();
          }
          return true;
        }

        @Override
        public T next() {
          if (!hasNext()) {
            throw new NoSuchElementException();
          }
          return items.next();
        }
      };
    }

    /**
     * A sequential stream over every item.
     *
     * @return the stream
     */
    public Stream<T> stream() {
      return StreamSupport.stream(
          Spliterators.spliteratorUnknownSize(iterator(), Spliterator.ORDERED), false);
    }
  }
}
