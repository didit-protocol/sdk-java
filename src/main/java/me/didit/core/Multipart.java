package me.didit.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;

/**
 * Encodes {@code multipart/form-data} (DESIGN 8). Strings, numbers and booleans become text fields;
 * a list of scalars becomes one field per item; objects and lists of objects become JSON text; null
 * fields are left out.
 */
final class Multipart {
  private static final SecureRandom RANDOM = new SecureRandom();

  private final String boundary;
  private final ByteArrayOutputStream out = new ByteArrayOutputStream();

  private Multipart(String boundary) {
    this.boundary = boundary;
  }

  static Multipart encode(ObjectNode fields, Map<String, List<FileUpload>> files) {
    byte[] random = new byte[16];
    RANDOM.nextBytes(random);
    StringBuilder boundary = new StringBuilder("didit-");
    for (byte b : random) {
      boundary.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
    }
    Multipart multipart = new Multipart(boundary.toString());
    for (Map.Entry<String, JsonNode> field : fields.properties()) {
      multipart.addValue(field.getKey(), field.getValue());
    }
    for (Map.Entry<String, List<FileUpload>> entry : files.entrySet()) {
      for (FileUpload file : entry.getValue()) {
        multipart.addFile(entry.getKey(), file);
      }
    }
    multipart.write("--" + multipart.boundary + "--\r\n");
    return multipart;
  }

  private void addValue(String name, JsonNode value) {
    if (value.isNull() || value.isMissingNode()) {
      return;
    }
    if (value.isArray()) {
      boolean scalars = true;
      for (JsonNode item : value) {
        scalars &= item.isValueNode();
      }
      if (scalars) {
        for (JsonNode item : value) {
          addValue(name, item);
        }
        return;
      }
      addText(name, Json.write(value));
      return;
    }
    if (value.isObject()) {
      addText(name, Json.write(value));
    } else if (value.isNumber()) {
      addText(name, value.decimalValue().toPlainString());
    } else {
      addText(name, value.asText());
    }
  }

  private void addText(String name, String value) {
    write("--" + boundary + "\r\n");
    write("Content-Disposition: form-data; name=\"" + quote(name) + "\"\r\n\r\n");
    write(value);
    write("\r\n");
  }

  private void addFile(String name, FileUpload file) {
    write("--" + boundary + "\r\n");
    write(
        "Content-Disposition: form-data; name=\""
            + quote(name)
            + "\"; filename=\""
            + quote(file.fileName())
            + "\"\r\n");
    write("Content-Type: " + file.contentType().replaceAll("[\\r\\n]", "") + "\r\n\r\n");
    out.writeBytes(file.read());
    write("\r\n");
  }

  private static String quote(String value) {
    return value.replace("\r", "").replace("\n", "").replace("\"", "%22");
  }

  private void write(String text) {
    out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
  }

  byte[] body() {
    return out.toByteArray();
  }

  String contentType() {
    return "multipart/form-data; boundary=" + boundary;
  }
}
