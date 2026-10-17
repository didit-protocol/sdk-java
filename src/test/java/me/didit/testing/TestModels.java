package me.didit.testing;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import me.didit.core.JsonModel;
import me.didit.core.JsonUnion;
import me.didit.core.MultiStatusResponse;
import me.didit.core.StringEnum;

/**
 * Calls every getter of a generated value, recursively, so a test fails when a field of a spec
 * example does not match its declared type.
 */
public final class TestModels {
  private static final Set<String> SKIP =
      Set.of(
          "toBuilder",
          "_json",
          "_additionalProperties",
          "_files",
          "hashCode",
          "toString",
          "getClass",
          "knownFields",
          "statusCode",
          "notify",
          "notifyAll",
          "wait");

  private TestModels() {}

  public static void readAll(Object value) {
    read(value, "result", new IdentityHashMap<>());
  }

  private static void read(Object value, String path, Map<Object, Boolean> seen) {
    if (value == null || seen.put(value, Boolean.TRUE) != null) {
      return;
    }
    if (value instanceof Optional) {
      ((Optional<?>) value).ifPresent(v -> read(v, path, seen));
      return;
    }
    if (value instanceof Collection) {
      int i = 0;
      for (Object item : (Collection<?>) value) {
        read(item, path + "[" + i++ + "]", seen);
      }
      return;
    }
    if (value instanceof Map) {
      for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
        read(e.getValue(), path + "." + e.getKey(), seen);
      }
      return;
    }
    if (value instanceof StringEnum) {
      return;
    }
    if (!(value instanceof JsonModel
        || value instanceof JsonUnion
        || value instanceof MultiStatusResponse)) {
      return;
    }
    for (Method method : value.getClass().getMethods()) {
      if (method.getParameterCount() != 0
          || Modifier.isStatic(method.getModifiers())
          || SKIP.contains(method.getName())
          || method.getDeclaringClass() == Object.class) {
        continue;
      }
      if (value instanceof JsonUnion || value instanceof MultiStatusResponse) {
        String name = method.getName();
        if (name.startsWith("as")) {
          String is = "is" + name.substring(2);
          try {
            if (!(Boolean) value.getClass().getMethod(is).invoke(value)) {
              continue;
            }
          } catch (ReflectiveOperationException e) {
            // Not a variant accessor: read it like any other getter.
          }
        }
      }
      try {
        read(method.invoke(value), path + "." + method.getName() + "()", seen);
      } catch (InvocationTargetException e) {
        throw new AssertionError(
            "Reading " + path + "." + method.getName() + "() failed: " + e.getCause(), e.getCause());
      } catch (IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }
  }
}
