package me.didit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import me.didit.testing.SpecExamples;
import org.junit.jupiter.api.Test;

/**
 * DESIGN 11: every {@code server: true} operation in {@code openapi/} has exactly one SDK method,
 * and nothing else exists. Reads the spec and the overlay directly, without the generator.
 */
final class ContractTest {
  private static final Set<String> HTTP = Set.of("get", "post", "put", "patch", "delete");

  /** {@code resource.method} of every server operation, from the spec plus the overlay. */
  static Set<String> specOperations() {
    JsonNode overlay = SpecExamples.document("overlay.json").get("operations");
    Set<String> out = new TreeSet<>();
    for (String[] spec : new String[][] {{"verification.json", ""}, {"account.json", "account "}}) {
      JsonNode doc = SpecExamples.document(spec[0]);
      for (Map.Entry<String, JsonNode> path : doc.get("paths").properties()) {
        for (Map.Entry<String, JsonNode> op : path.getValue().properties()) {
          if (!HTTP.contains(op.getKey())) {
            continue;
          }
          JsonNode sdk = op.getValue().get("x-didit-sdk");
          if (sdk == null) {
            sdk = overlay.get(spec[1] + op.getKey().toUpperCase(java.util.Locale.ROOT) + " " + path.getKey());
          }
          if (sdk == null) {
            throw new AssertionError("No SDK naming for " + op.getKey() + " " + path.getKey());
          }
          if (sdk.path("server").asBoolean()) {
            out.add(sdk.get("resource").asText() + "." + sdk.get("method").asText());
          }
        }
      }
    }
    JsonNode deprecated = deprecatedOperations();
    deprecated.fieldNames().forEachRemaining(out::add);
    return out;
  }

  static JsonNode deprecatedOperations() {
    java.nio.file.Path file = SpecExamples.repoRoot().resolve("openapi/deprecated-operations.json");
    if (!java.nio.file.Files.exists(file)) {
      return me.didit.core.Json.nodes().objectNode();
    }
    return SpecExamples.document("deprecated-operations.json").path("methods");
  }

  /** {@code resource.method} of every operation method reachable from {@link Didit}. */
  static Set<String> sdkOperations() {
    Set<String> out = new TreeSet<>();
    walk(Didit.class, "", out, new TreeMap<>());
    return out;
  }

  private static void walk(Class<?> type, String resource, Set<String> out, Map<String, Class<?>> seen) {
    for (Method m : type.getMethods()) {
      if (Modifier.isStatic(m.getModifiers()) || m.getDeclaringClass() == Object.class) {
        continue;
      }
      Class<?> returns = m.getReturnType();
      if (m.getParameterCount() == 0
          && returns.getPackageName().equals("me.didit.resources")
          && returns.getSimpleName().endsWith("Resource")) {
        String child = (resource.isEmpty() ? "" : resource + ".") + snake(m.getName());
        if (seen.put(child, returns) == null) {
          walk(returns, child, out, seen);
        }
        continue;
      }
      if (resource.isEmpty()
          || !m.getDeclaringClass().getPackageName().equals("me.didit.resources")
          || m.getName().endsWith("Async")
          || m.getName().equals("withRawResponse")) {
        continue;
      }
      out.add(resource + "." + snake(m.getName()));
    }
  }

  static String snake(String camel) {
    StringBuilder out = new StringBuilder();
    for (char c : camel.replaceAll("_$", "").toCharArray()) {
      if (Character.isUpperCase(c)) {
        out.append('_').append(Character.toLowerCase(c));
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  @Test
  void everyServerOperationHasExactlyOneMethod() {
    Set<String> spec = specOperations();
    Set<String> sdk = sdkOperations();
    Set<String> missing = new TreeSet<>(spec);
    missing.removeAll(sdk);
    Set<String> extra = new TreeSet<>(sdk);
    extra.removeAll(spec);
    assertEquals(Set.of(), missing, "operations without an SDK method");
    assertEquals(Set.of(), extra, "SDK methods without an operation");
    assertEquals(spec.size(), sdk.size());
    assertTrue(spec.size() >= 257, "248 server operations plus 9 account operations");
  }

  @Test
  void deprecatedMethodsLeaveOnTime() {
    JsonNode deprecated = deprecatedOperations();
    for (Map.Entry<String, JsonNode> entry : deprecated.properties()) {
      String removeIn = entry.getValue().path("remove_in").asText("");
      assertTrue(
          compareVersions(me.didit.core.Version.VERSION, removeIn) < 0,
          entry.getKey() + " was due for removal in " + removeIn + ": delete it from openapi/deprecated-operations.json");
    }
    assertTrue(compareVersions("0.1.0", "0.2.0") < 0);
    assertTrue(compareVersions("1.10.0", "1.9.0") > 0);
    assertEquals(0, compareVersions("1.0.0", "1.0.0"));
  }

  static int compareVersions(String a, String b) {
    String[] x = a.split("[.-]");
    String[] y = b.split("[.-]");
    for (int i = 0; i < Math.min(3, Math.min(x.length, y.length)); i++) {
      int cmp = Integer.compare(Integer.parseInt(x[i]), Integer.parseInt(y[i]));
      if (cmp != 0) {
        return cmp;
      }
    }
    return 0;
  }

  @Test
  void everyOperationHasSyncAsyncAndRawVariants() throws Exception {
    Map<String, Class<?>> resources = new TreeMap<>();
    walk(Didit.class, "", new TreeSet<>(), resources);
    for (Map.Entry<String, Class<?>> e : resources.entrySet()) {
      Class<?> type = e.getValue();
      Set<String> names = new TreeSet<>();
      for (Method m : type.getDeclaredMethods()) {
        if (Modifier.isPublic(m.getModifiers()) && !m.getName().equals("withRawResponse")
            && !m.getReturnType().getSimpleName().endsWith("Resource")) {
          names.add(m.getName());
        }
      }
      for (String name : names) {
        if (name.endsWith("Async")) {
          continue;
        }
        assertTrue(names.contains(name + "Async"), e.getKey() + "." + name + "Async");
        Class<?> raw = Class.forName(type.getName() + "$WithRawResponse");
        boolean hasRaw = false;
        boolean hasRawAsync = false;
        for (Method m : raw.getDeclaredMethods()) {
          hasRaw |= m.getName().equals(name);
          hasRawAsync |= m.getName().equals(name + "Async");
        }
        assertTrue(hasRaw && hasRawAsync, e.getKey() + "." + name + " raw variants");
      }
    }
  }
}
