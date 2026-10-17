package me.didit.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;

/**
 * A binary response such as a PDF report or a CSV template (DESIGN 8). Read it once with {@link
 * #bytes()}, {@link #writeTo(Path)} or {@link #stream()}; close it when you stream.
 */
public final class BinaryResponse implements AutoCloseable {
  private final Headers headers;
  private final InputStream body;
  private boolean consumed;

  BinaryResponse(Headers headers, InputStream body) {
    this.headers = headers;
    this.body = body;
  }

  /**
   * Creates a response, for tests.
   *
   * @param headers the response headers
   * @param body the content
   * @return the response
   */
  public static BinaryResponse of(Headers headers, byte[] body) {
    return new BinaryResponse(headers, new java.io.ByteArrayInputStream(body.clone()));
  }

  /**
   * The content type, for example {@code application/pdf}.
   *
   * @return the content type, if the response declared one
   */
  public Optional<String> contentType() {
    return headers.firstValue("Content-Type");
  }

  /**
   * The file name from {@code Content-Disposition}, if any.
   *
   * @return the file name
   */
  public Optional<String> fileName() {
    String disposition = headers.firstValue("Content-Disposition").orElse("");
    for (String part : disposition.split(";")) {
      String p = part.trim();
      if (p.toLowerCase(Locale.ROOT).startsWith("filename=")) {
        String name = p.substring("filename=".length()).trim();
        if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
          name = name.substring(1, name.length() - 1);
        }
        return name.isEmpty() ? Optional.empty() : Optional.of(name);
      }
    }
    return Optional.empty();
  }

  /**
   * The response headers.
   *
   * @return the headers
   */
  public Headers headers() {
    return headers;
  }

  /**
   * Reads the whole content and closes the response.
   *
   * @return the content
   * @throws UncheckedIOException when reading fails
   */
  public synchronized byte[] bytes() {
    claim();
    try (InputStream in = body) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read the response body", e);
    }
  }

  /**
   * Writes the content to a file, replacing it, and closes the response.
   *
   * @param path the target file
   * @return the number of bytes written
   * @throws UncheckedIOException when reading or writing fails
   */
  public synchronized long writeTo(Path path) {
    claim();
    try (InputStream in = body) {
      return Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write " + path, e);
    }
  }

  /**
   * The content as a stream. Close the stream, or this response, when done.
   *
   * @return the stream
   */
  public synchronized InputStream stream() {
    claim();
    return body;
  }

  private void claim() {
    if (consumed) {
      throw new IllegalStateException("The response body was already read");
    }
    consumed = true;
  }

  @Override
  public void close() {
    try {
      body.close();
    } catch (IOException e) {
      // Nothing to do: the body is discarded.
    }
  }

  @Override
  public String toString() {
    return "BinaryResponse{contentType=" + contentType().orElse("unknown") + "}";
  }
}
