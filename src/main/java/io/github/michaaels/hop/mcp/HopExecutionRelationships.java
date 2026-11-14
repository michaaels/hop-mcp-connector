package io.github.michaaels.hop.mcp;

import org.apache.hop.execution.Execution;

/**
 * Reads native Hop 2.20 owner/launcher relationships without guessing legacy document semantics.
 */
final class HopExecutionRelationships {
  private HopExecutionRelationships() {}

  record Relationship(String parentId, String ownerId, String launcherId, String source) {}

  static Relationship read(Execution execution) throws McpException {
    String parent = text(execution.getParentId());
    String launcher;
    try {
      launcher = text(execution.getLaunchedById());
    } catch (RuntimeException incompatible) {
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
