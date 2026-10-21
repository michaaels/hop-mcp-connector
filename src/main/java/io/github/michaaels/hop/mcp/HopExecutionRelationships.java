package io.github.michaaels.hop.mcp;

import java.lang.reflect.Method;
import java.util.Optional;
import org.apache.hop.execution.Execution;

/** Reads the optional Hop 2.20 launcher API without linking the 2.19 artifact against it. */
final class HopExecutionRelationships {
  private static final ClassValue<Optional<Method>> LAUNCHER_GETTERS =
      new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
          try {
            Method getter = type.getMethod("getLaunchedById");
            if (getter.getReturnType() != String.class) {
              throw new IllegalStateException("Unsupported native execution launcher API");
            }
            return Optional.of(getter);
          } catch (NoSuchMethodException absentInHop219) {
            return Optional.empty();
          }
        }
      };

  private HopExecutionRelationships() {}

  record Relationship(String parentId, String ownerId, String launcherId, String source) {}

  static Relationship read(Execution execution) throws McpException {
    String parent = text(execution.getParentId());
    String launcher = "";
    try {
      Optional<Method> getter = LAUNCHER_GETTERS.get(execution.getClass());
      if (getter.isPresent()) launcher = text((String) getter.get().invoke(execution));
    } catch (ReflectiveOperationException | RuntimeException incompatible) {
      // A missing method is expected on 2.19. A present but failing method must not silently
      // turn a modern record into a legacy one or expose an exception containing native secrets.
      throw McpException.precondition(
          "EXECUTION_RELATIONSHIP_UNAVAILABLE",
          "Unable to read the native execution launcher.",
          false);
    }
    if (parent.isEmpty() && launcher.isEmpty()) return new Relationship("", "", "", "none");
    if (launcher.isEmpty() || parent.equals(launcher)) {
      // The same 2.20 location can contain old documents. API availability alone does not prove
      // whether parentId in an individual record is an owner or a component log channel.
      return new Relationship(parent, "", launcher, "unresolved");
    }
    if (parent.isEmpty()) return new Relationship("", "", launcher, "launcher_only");
    return new Relationship(parent, parent, launcher, "owner_and_launcher");
  }

  private static String text(String value) {
    return value == null || value.isBlank() ? "" : value;
  }
}
