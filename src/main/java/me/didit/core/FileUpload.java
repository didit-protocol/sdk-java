package me.didit.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A file for a multipart upload: a path, bytes or a stream, with an optional file name and content
 * type. Streams are read once, when the request is built, so that retries resend the same bytes.
 */
public final class FileUpload {
  private final @Nullable Path path;
  private final byte @Nullable [] bytes;
  private final @Nullable InputStream stream;
  private final String fileName;
  private final @Nullable String contentType;
  private byte @Nullable [] streamBytes;

  private FileUpload(
      @Nullable Path path,
      byte @Nullable [] bytes,
      @Nullable InputStream stream,
      String fileName,
      @Nullable String contentType) {
    this.path = path;
    this.bytes = bytes;
    this.stream = stream;
    this.fileName = fileName;
    this.contentType = contentType;
  }

  /**
   * A file on disk, read when the request is sent. The file name is the path's last segment.
   *
   * @param path the file
   * @return the upload
   */
  public static FileUpload of(Path path) {
    Path name = path.getFileName();
    return new FileUpload(path, null, null, name == null ? "file" : name.toString(), null);
  }

  /**
   * In-memory bytes.
   *
   * @param bytes the content; copied
   * @param fileName the file name sent to the API, for example {@code front.jpg}
   * @return the upload
   */
  public static FileUpload of(byte[] bytes, String fileName) {
    return new FileUpload(null, bytes.clone(), null, fileName, null);
  }

  /**
   * In-memory bytes with an explicit content type.
   *
   * @param bytes the content; copied
   * @param fileName the file name sent to the API
   * @param contentType the content type, for example {@code image/jpeg}
   * @return the upload
   */
  public static FileUpload of(byte[] bytes, String fileName, String contentType) {
    return new FileUpload(null, bytes.clone(), null, fileName, contentType);
  }

  /**
   * A stream, read fully the first time the request is built. The SDK does not close it.
   *
   * @param stream the content
   * @param fileName the file name sent to the API
   * @param contentType the content type, or {@code null} to guess it from the file name
   * @return the upload
   */
  public static FileUpload of(InputStream stream, String fileName, @Nullable String contentType) {
    return new FileUpload(null, null, Objects.requireNonNull(stream, "stream"), fileName, contentType);
  }

  /**
   * The same content with another content type.
   *
   * @param contentType the content type
   * @return a new upload
   */
  public FileUpload withContentType(String contentType) {
    return new FileUpload(path, bytes, stream, fileName, contentType);
  }

  /**
   * The same content with another file name.
   *
   * @param fileName the file name
   * @return a new upload
   */
  public FileUpload withFileName(String fileName) {
    return new FileUpload(path, bytes, stream, fileName, contentType);
  }

  /**
   * The file name sent to the API.
   *
   * @return the file name
   */
  public String fileName() {
    return fileName;
  }

  /**
   * The content type: the one given, or a guess from the file name.
   *
   * @return the content type
   */
  public String contentType() {
    if (contentType != null) {
      return contentType;
    }
    return guessContentType(fileName).orElse("application/octet-stream");
  }

  /**
   * Reads the content.
   *
   * @return the bytes
   * @throws UncheckedIOException when the file or stream cannot be read
   */
  public synchronized byte[] read() {
    if (bytes != null) {
      return bytes.clone();
    }
    if (path != null) {
      try {
        return Files.readAllBytes(path);
      } catch (IOException e) {
        throw new UncheckedIOException("Cannot read " + path, e);
      }
    }
    if (streamBytes == null) {
      try {
        streamBytes = Objects.requireNonNull(stream).readAllBytes();
      } catch (IOException e) {
        throw new UncheckedIOException("Cannot read the upload stream", e);
      }
    }
    return streamBytes.clone();
  }

  static Optional<String> guessContentType(String fileName) {
    String lower = fileName.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
      return Optional.of("image/jpeg");
    }
    if (lower.endsWith(".png")) {
      return Optional.of("image/png");
    }
    if (lower.endsWith(".pdf")) {
      return Optional.of("application/pdf");
    }
    if (lower.endsWith(".csv")) {
      return Optional.of("text/csv");
    }
    if (lower.endsWith(".webp")) {
      return Optional.of("image/webp");
    }
    if (lower.endsWith(".heic")) {
      return Optional.of("image/heic");
    }
    return Optional.ofNullable(URLConnection.guessContentTypeFromName(fileName));
  }

  @Override
  public String toString() {
    return "FileUpload{fileName=" + fileName + ", contentType=" + contentType() + "}";
  }
}
