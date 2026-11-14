package io.github.michaaels.hop.mcp;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.hop.core.Const;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.core.variables.Variables;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Declared definition-file references and their confined, file-local variable context. */
final class HopDefinitionReferences {
  private static final Set<String> FILE_FIELDS =
      Set.of(
          "filename",
          "pipeline",
          "pipeline_filename",
          "workflow_filename",
          "pipeline_file",
          "workflow_file",
          "pipelinepath",
          "workflowpath",
          "transformationpath");
  private static final Set<String> INFORMATIONAL =
      Set.of(
          "info",
          "description",
          "extended_description",
          "notepad",
          "notepads",
          "note",
          "script",
          "jsscript_script",
          "sql",
          "password",
          "token",
          "secret",
          "client_secret");
  private static final Set<String> LOADERS =
      Set.of(
          "pipeline",
          "workflow",
          "workflowexecutor",
          "pipelineexecutor",
          "mapping",
          "simplemapping",
          "inject",
          "etlmetadatainjection");
  private static final Pattern UNRESOLVED = Pattern.compile("\\$\\{[^}]+}|%%[^%]+%%");

  private HopDefinitionReferences() {}

  static Set<String> extract(Element root, int maxItems) {
    Set<String> references = new LinkedHashSet<>();
    var elements = root.getElementsByTagName("*");
    for (int index = 0; index < elements.getLength() && references.size() < maxItems; index++) {
      Element field = (Element) elements.item(index);
      if (!FILE_FIELDS.contains(field.getTagName().toLowerCase(Locale.ROOT))
          || field.getElementsByTagName("*").getLength() != 0
          || informational(field)) continue;
      String value = field.getTextContent().trim();
      String lower = value.toLowerCase(Locale.ROOT);
      if (!lower.endsWith(".hpl")
          && !lower.endsWith(".hwf")
          && !(UNRESOLVED.matcher(value).find() && loaderField(field))) continue;
      if (value.length() > 4096)
        throw new IllegalArgumentException("Definition reference exceeds 4096 characters");
      references.add(HopXml.redact(value));
    }
    return references;
  }

  private static boolean informational(Element field) {
    for (Node parent = field.getParentNode();
        parent instanceof Element element;
        parent = parent.getParentNode()) {
      if (INFORMATIONAL.contains(element.getTagName().toLowerCase(Locale.ROOT))) return true;
    }
    return false;
  }

  private static boolean loaderField(Element field) {
    for (Node parent = field.getParentNode();
        parent instanceof Element element;
        parent = parent.getParentNode()) {
      if ("transform".equals(element.getTagName()) || "action".equals(element.getTagName())) {
        String type = HopXml.childText(element, "type");
        return type != null && LOADERS.contains(type.toLowerCase(Locale.ROOT));
      }
    }
    return false;
  }

  static Path resolve(ProjectFiles files, IVariables inherited, Path definition, String reference)
      throws Exception {
    if (reference == null || reference.isBlank() || reference.length() > 4096)
      throw new IllegalArgumentException("A bounded definition reference is required");
    Path folder = definition.getParent();
    Variables scoped = new Variables();
    if (inherited != null) scoped.initializeFrom(inherited);
    scoped.setVariable("PROJECT_HOME", files.root().toString());
    scoped.setVariable(Const.INTERNAL_VARIABLE_ENTRY_CURRENT_FOLDER, folder.toString());
    scoped.setVariable(Const.INTERNAL_VARIABLE_PIPELINE_FILENAME_DIRECTORY, folder.toString());
    scoped.setVariable(Const.INTERNAL_VARIABLE_WORKFLOW_FILENAME_FOLDER, folder.toString());
    scoped.setVariable(
        Const.INTERNAL_VARIABLE_PIPELINE_FILENAME_NAME, definition.getFileName().toString());
    scoped.setVariable(
        Const.INTERNAL_VARIABLE_WORKFLOW_FILENAME_NAME, definition.getFileName().toString());
    String resolved = scoped.resolve(reference);
    if (resolved == null || UNRESOLVED.matcher(resolved).find())
      throw new UnresolvedReferenceException();
    Path raw;
    if (resolved.regionMatches(true, 0, "file:", 0, 5)) {
      raw = Path.of(URI.create(resolved.replace(" ", "%20")));
    } else {
      if (resolved.contains("://"))
        throw new IllegalArgumentException("Only project-local file references are supported");
      raw = Path.of(resolved);
    }
    Path candidate = (raw.isAbsolute() ? raw : folder.resolve(raw)).normalize();
    if (!candidate.startsWith(files.root()))
      throw McpException.security(
          "PATH_OUTSIDE_PROJECT", "Dependency must remain under the configured project root.");
    files.requirePublicPath(candidate);
    Path existing = candidate;
    while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
    Path realAncestor = existing.toRealPath();
    if (!realAncestor.startsWith(files.root()))
      throw McpException.security(
          "PATH_OUTSIDE_PROJECT", "Dependency must remain under the configured project root.");
    Path canonical = realAncestor.resolve(existing.relativize(candidate)).normalize();
    files.requirePublicPath(canonical);
    return canonical;
  }

  static final class UnresolvedReferenceException extends IllegalArgumentException {
    UnresolvedReferenceException() {
      super("Dependency contains variables that are not defined in this file's context.");
    }
  }
}
