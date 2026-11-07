package me.didit.testing;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.didit.core.TransportRequest;
import org.jspecify.annotations.Nullable;

/** A request seen by {@link MockTransport}, with helpers to read it. */
public final class RecordedRequest {
  private final TransportRequest request;

  RecordedRequest(TransportRequest request) {
    this.request = request;
  }

  public TransportRequest raw() {
    return request;
  }

  public String method() {
    return request.method();
  }

  public URI uri() {
    return request.uri();
  }

  public String origin() {
    URI u = request.uri();
    return u.getScheme() + "://" + u.getAuthority();
  }

  /** The decoded path, without the base URL's path prefix for the account API. */
  public String path() {
    String path = request.uri().getRawPath();
    if (path.startsWith("/auth/v2/")) {
      path = path.substring("/auth/v2".length());
    }
    return URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8);
  }

  public Map<String, List<String>> query() {
    Map<String, List<String>> out = new LinkedHashMap<>();
    String raw = request.uri().getRawQuery();
    if (raw == null || raw.isEmpty()) {
      return out;
    }
    for (String pair : raw.split("&", -1)) {
      int eq = pair.indexOf('=');
      String name = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
      String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      out.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
    }
    return out;
  }

  public @Nullable String header(String name) {
    for (Map.Entry<String, List<String>> e : request.headers().entrySet()) {
      if (e.getKey().equalsIgnoreCase(name)) {
        return e.getValue().get(0);
      }
    }
    return null;
  }

  public byte[] body() {
    return request.body();
  }

  public String bodyText() {
    return new String(request.body(), StandardCharsets.UTF_8);
  }

  /** Text fields of a multipart body, by name, in order. */
  public Map<String, List<String>> multipartFields() {
    Map<String, List<String>> out = new LinkedHashMap<>();
    for (Part part : parts()) {
      if (part.fileName == null) {
        out.computeIfAbsent(part.name, k -> new ArrayList<>()).add(part.content);
      }
    }
    return out;
  }

  /** File names of a multipart body, by field name. */
  public Map<String, List<String>> multipartFiles() {
    Map<String, List<String>> out = new LinkedHashMap<>();
    for (Part part : parts()) {
      if (part.fileName != null) {
        out.computeIfAbsent(part.name, k -> new ArrayList<>()).add(part.fileName);
      }
    }
    return out;
  }

  /** A multipart part. */
  public static final class Part {
    public final String name;
    public final @Nullable String fileName;
    public final @Nullable String contentType;
    public final String content;

    Part(String name, @Nullable String fileName, @Nullable String contentType, String content) {
      this.name = name;
      this.fileName = fileName;
      this.contentType = contentType;
      this.content = content;
    }
  }

  private static final Pattern NAME = Pattern.compile("name=\"([^\"]*)\"");
  private static final Pattern FILE = Pattern.compile("filename=\"([^\"]*)\"");

  public List<Part> parts() {
    String contentType = header("Content-Type");
    if (contentType == null
        || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
      throw new AssertionError("Not a multipart request: " + contentType);
    }
    String boundary = contentType.substring(contentType.indexOf("boundary=") + 9);
    String body = new String(request.body(), StandardCharsets.ISO_8859_1);
    List<Part> parts = new ArrayList<>();
    for (String chunk : body.split("--" + Pattern.quote(boundary), -1)) {
      if (chunk.isEmpty() || chunk.startsWith("--")) {
        continue;
      }
      String c = chunk.startsWith("\r\n") ? chunk.substring(2) : chunk;
      int split = c.indexOf("\r\n\r\n");
      String head = c.substring(0, split);
      String content = c.substring(split + 4, c.length() - 2);
      Matcher name = NAME.matcher(head);
      Matcher file = FILE.matcher(head);
      String type = null;
      for (String line : head.split("\r\n", -1)) {
        if (line.toLowerCase(Locale.ROOT).startsWith("content-type:")) {
          type = line.substring("content-type:".length()).trim();
        }
      }
      parts.add(
          new Part(
              name.find() ? name.group(1) : "",
              file.find() ? file.group(1) : null,
              type,
              new String(content.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8)));
    }
    return parts;
  }

  @Override
  public String toString() {
    return request.toString();
  }
}
