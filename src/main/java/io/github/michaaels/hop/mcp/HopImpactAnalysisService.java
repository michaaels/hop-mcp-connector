package io.github.michaaels.hop.mcp;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.hop.core.variables.IVariables;

/** Bounded project-local impact graph built from an incremental definition index. */
final class HopImpactAnalysisService {
  static final int MAX_DEPTH = 64;
  static final int MAX_EDGES = 500;
  static final int MAX_RESULTS = ProjectFiles.MAX_STRUCTURED_RESULTS;
  static final int MAX_TABLE_REFERENCES = 200;
  static final int MAX_METADATA_REFERENCES = 200;
  static final int MAX_PATH_LENGTH = 4096;
  static final int MAX_SELECTOR_LENGTH = 1024;
  static final int MAX_GRAPH_REFERENCES = 50_000;

  private final ProjectFiles files;
  private final IVariables variables;
  private final HopProjectDefinitionIndex definitionIndex;

  HopImpactAnalysisService(ProjectFiles files) {
    this(files, null, new HopProjectDefinitionIndex(files));
  }

  HopImpactAnalysisService(ProjectFiles files, IVariables variables) {
    this(files, variables, new HopProjectDefinitionIndex(files));
  }

  HopImpactAnalysisService(
      ProjectFiles files, IVariables variables, HopProjectDefinitionIndex definitionIndex) {
    this.files = files;
    this.variables = variables;
    this.definitionIndex = definitionIndex;
  }

  Map<String, Object> analyze(
      String table, String metadata, String definition, int maxDepth, int maxEdges, int maxResults)
      throws Exception {
    Selector selector = selector(table, metadata, definition);
    validateBounds(maxDepth, maxEdges, maxResults);
    if (selector.isDefinition()) {
      Path requested = files.resolve(selector.definition());
      selector = Selector.definition(files.relative(requested));
    }

    HopProjectDefinitionIndex.Snapshot snapshot = definitionIndex.snapshot();
    Map<String, HopProjectDefinitionIndex.Entry> definitions =
        new LinkedHashMap<>(snapshot.definitions());
    if (selector.isDefinition() && !definitions.containsKey(selector.definition())) {
      HopProjectDefinitionIndex.Entry requested =
          definitionIndex.readSingle(files.resolve(selector.definition()));
      definitions.put(requested.path(), requested);
    }

    Map<String, Set<String>> inbound = new LinkedHashMap<>();
    boolean referenceTruncated = snapshot.truncated();
    boolean graphTruncated = false;
    int graphReferences = 0;
    graphBuild:
    for (HopProjectDefinitionIndex.Entry current : definitions.values()) {
      referenceTruncated |=
          current.referencesTruncated()
              || current.tablesTruncated()
              || current.metadataReferencesTruncated();
      for (String reference : current.references()) {
        if (graphReferences >= MAX_GRAPH_REFERENCES) {
          graphTruncated = true;
          break graphBuild;
        }
        Path resolved = resolveReference(current.path(), reference);
        if (resolved == null) continue;
        String target = files.relative(resolved);
        if (!definitions.containsKey(target)) continue;
        inbound.computeIfAbsent(target, ignored -> new LinkedHashSet<>()).add(current.path());
        graphReferences++;
      }
    }

    Set<String> initial = matchingDefinitions(definitions, selector);
    Traversal traversal = traverse(initial, inbound, definitions, maxDepth, maxEdges, maxResults);
    List<Map<String, Object>> nodes = nodeOutput(traversal.nodes(), definitions);
    List<Map<String, Object>> tableReferences =
        tableReferences(selector, traversal.nodes(), definitions);
    List<Map<String, Object>> metadataReferences =
        metadataReferences(selector, traversal.nodes(), definitions);
    List<Map<String, Object>> lineage = lineage(traversal.nodes(), definitions, maxEdges);
    EdgeOutput dependencies = dependencyEdges(traversal.nodes(), definitions, maxEdges);

    boolean truncated =
        referenceTruncated
            || graphTruncated
            || traversal.truncated()
            || dependencies.truncated()
            || tableReferences.size() >= MAX_TABLE_REFERENCES
            || metadataReferences.size() >= MAX_METADATA_REFERENCES
            || lineage.size() >= maxEdges;

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("query", selector.output());
    result.put("nodes", nodes);
    result.put("dependencies", dependencies.edges());
    result.put("metadata_references", metadataReferences);
    result.put("table_references", tableReferences);
    result.put("pipeline_workflow_references", dependencies.edges());
    result.put("lineage", lineage);
    result.put("node_count", traversal.nodes().size());
    result.put("returned_nodes", nodes.size());
    result.put("edge_count", dependencies.edges().size());
    result.put("returned_edges", dependencies.edges().size());
    result.put("max_depth_applied", maxDepth);
    result.put("max_edges_applied", maxEdges);
    result.put("max_results_applied", maxResults);
    result.put("count_complete", !truncated);
    result.put("results_truncated", truncated);
    result.put("has_more", truncated);
    result.put("redaction_applied", true);
    return result;
  }

  private static Set<String> matchingDefinitions(
      Map<String, HopProjectDefinitionIndex.Entry> definitions, Selector selector) {
    Set<String> result = new LinkedHashSet<>();
    for (HopProjectDefinitionIndex.Entry definition : definitions.values()) {
      if (selector.isDefinition() && selector.definition().equals(definition.path())) {
        result.add(definition.path());
      } else if (selector.isTable() && matchesTable(definition.tables(), selector.table())) {
        result.add(definition.path());
      } else if (selector.isMetadata() && hasMetadataReference(definition, selector)) {
        result.add(definition.path());
      }
    }
    return result;
  }

  private static Traversal traverse(
      Set<String> initial,
      Map<String, Set<String>> graph,
      Map<String, HopProjectDefinitionIndex.Entry> definitions,
      int maxDepth,
      int maxEdges,
      int maxResults) {
    ArrayDeque<QueueItem> queue = new ArrayDeque<>();
    Map<String, Integer> depths = new LinkedHashMap<>();
    for (String path : new TreeSet<>(initial)) {
      if (definitions.containsKey(path)) {
        depths.put(path, 0);
        queue.add(new QueueItem(path, 0));
      }
    }

    int traversedEdges = 0;
    boolean truncated = false;
    while (!queue.isEmpty()) {
      QueueItem current = queue.remove();
      if (current.depth() >= maxDepth) continue;
      for (String next : new TreeSet<>(graph.getOrDefault(current.path(), Set.of()))) {
        if (++traversedEdges > maxEdges) {
          truncated = true;
          break;
        }
        if (depths.containsKey(next)) continue;
        if (depths.size() >= maxResults) {
          truncated = true;
          break;
        }
        depths.put(next, current.depth() + 1);
        queue.add(new QueueItem(next, current.depth() + 1));
      }
      if (truncated) break;
    }
    return new Traversal(depths, truncated);
  }

  private static List<Map<String, Object>> nodeOutput(
      Map<String, Integer> nodes, Map<String, HopProjectDefinitionIndex.Entry> definitions) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map.Entry<String, Integer> node : nodes.entrySet()) {
      HopProjectDefinitionIndex.Entry definition = definitions.get(node.getKey());
      if (definition == null) continue;
      result.add(
          Map.of(
              "path", definition.path(),
              "kind", definition.kind(),
              "depth", node.getValue()));
    }
    return result;
  }

  private static List<Map<String, Object>> tableReferences(
      Selector selector,
      Map<String, Integer> nodes,
      Map<String, HopProjectDefinitionIndex.Entry> definitions) {
    if (!selector.isTable()) return List.of();
    List<Map<String, Object>> result = new ArrayList<>();
    for (String path : nodes.keySet()) {
      HopProjectDefinitionIndex.Entry definition = definitions.get(path);
      if (definition == null) continue;
      for (String table : definition.tables()) {
        if (matchesTable(Set.of(table), selector.table())) {
          result.add(Map.of("path", path, "table", safe(table)));
          if (result.size() >= MAX_TABLE_REFERENCES) return result;
        }
      }
    }
    return result;
  }

  private static List<Map<String, Object>> metadataReferences(
      Selector selector,
      Map<String, Integer> nodes,
      Map<String, HopProjectDefinitionIndex.Entry> definitions) {
    if (!selector.isMetadata()) return List.of();
    List<Map<String, Object>> result = new ArrayList<>();
    for (String path : nodes.keySet()) {
      HopProjectDefinitionIndex.Entry definition = definitions.get(path);
      if (definition == null) continue;
      for (HopProjectDefinitionIndex.MetadataReference reference :
          definition.metadataReferences()) {
        if (!matchesMetadataReference(reference, selector)) continue;
        result.add(
            Map.of(
                "path",
                path,
                "metadata",
                safe(reference.name()),
                "type",
                safe(reference.type()),
                "component",
                safe(reference.component()),
                "reference_source",
                reference.source().name().toLowerCase(Locale.ROOT)));
        if (result.size() >= MAX_METADATA_REFERENCES) return result;
      }
    }
    return result;
  }

  private static List<Map<String, Object>> lineage(
      Map<String, Integer> nodes,
      Map<String, HopProjectDefinitionIndex.Entry> definitions,
      int maxEdges) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (String path : nodes.keySet()) {
      HopProjectDefinitionIndex.Entry definition = definitions.get(path);
      if (definition == null) continue;
      Object value = definition.inspection().get("hops");
      if (!(value instanceof List<?> hops)) continue;
      for (Object item : hops) {
        if (!(item instanceof Map<?, ?> hop)) continue;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("path", path);
        row.put("from", safe(String.valueOf(hop.containsKey("from") ? hop.get("from") : "")));
        row.put("to", safe(String.valueOf(hop.containsKey("to") ? hop.get("to") : "")));
        row.put("depth", 1);
        result.add(row);
        if (result.size() >= maxEdges) return result;
      }
    }
    return result;
  }

  private EdgeOutput dependencyEdges(
      Map<String, Integer> nodes,
      Map<String, HopProjectDefinitionIndex.Entry> definitions,
      int maxEdges) {
    Set<String> included = nodes.keySet();
    List<Map<String, Object>> result = new ArrayList<>();
    boolean truncated = false;
    for (String source : included) {
      HopProjectDefinitionIndex.Entry current = definitions.get(source);
      if (current == null) continue;
      for (String reference : current.references()) {
        Path resolved = resolveReference(source, reference);
        if (resolved == null) continue;
        String target = files.relative(resolved);
        if (!included.contains(target)) continue;
        if (result.size() >= maxEdges) {
          truncated = true;
          return new EdgeOutput(result, true);
        }
        HopProjectDefinitionIndex.Entry targetDefinition = definitions.get(target);
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("from", source);
        edge.put("to", target);
        edge.put("type", "definition_reference");
        edge.put("from_kind", current.kind());
        edge.put("to_kind", targetDefinition == null ? kind(target) : targetDefinition.kind());
        result.add(edge);
      }
    }
    return new EdgeOutput(result, truncated);
  }

  private Path resolveReference(String source, String reference) {
    try {
      if (reference == null || reference.isBlank() || reference.length() > MAX_PATH_LENGTH) {
        return null;
      }
      String resolvedReference = reference.replace("${PROJECT_HOME}", files.root().toString());
      if (variables != null) resolvedReference = variables.resolve(resolvedReference);
      if (resolvedReference == null || resolvedReference.isBlank()) return null;
      Path sourcePath = files.root().resolve(source).normalize();
      Path sourceFolder = sourcePath.getParent();
      if (sourceFolder == null) return null;
      resolvedReference =
          resolvedReference.replace("${Internal.Entry.Current.Folder}", sourceFolder.toString());
      Path raw = Path.of(resolvedReference);
      Path candidate = (raw.isAbsolute() ? raw : sourceFolder.resolve(raw)).normalize();
      return candidate.startsWith(files.root()) ? candidate : null;
    } catch (Exception ignored) {
      return null;
    }
  }

  private static boolean hasMetadataReference(
      HopProjectDefinitionIndex.Entry definition, Selector selector) {
    return definition.metadataReferences().stream()
        .anyMatch(reference -> matchesMetadataReference(reference, selector));
  }

  private static boolean matchesMetadataReference(
      HopProjectDefinitionIndex.MetadataReference reference, Selector selector) {
    return reference.name().equalsIgnoreCase(selector.metadataName())
        && (selector.metadataType() == null
            || reference.type().equalsIgnoreCase(selector.metadataType()));
  }

  private static Selector selector(String table, String metadata, String definition)
      throws Exception {
    int count = count(table) + count(metadata) + count(definition);
    if (count != 1) {
      throw new IllegalArgumentException(
          "Exactly one of table, metadata or definition is required");
    }
    if (count(table) == 1) return Selector.table(requireSelector(table, "table"));
    if (count(metadata) == 1) {
      return Selector.metadata(requireSelector(metadata, "metadata"));
    }
    return Selector.definition(requireSelector(definition, "definition"));
  }

  private static int count(String value) {
    return value == null || value.isBlank() ? 0 : 1;
  }

  private static String requireSelector(String value, String field) {
    if (value.length() > MAX_SELECTOR_LENGTH) {
      throw new IllegalArgumentException(field + " exceeds " + MAX_SELECTOR_LENGTH + " characters");
    }
    return value;
  }

  private static void validateBounds(int maxDepth, int maxEdges, int maxResults) {
    if (maxDepth < 1 || maxDepth > MAX_DEPTH) {
      throw new IllegalArgumentException("max_depth must be between 1 and " + MAX_DEPTH);
    }
    if (maxEdges < 1 || maxEdges > MAX_EDGES) {
      throw new IllegalArgumentException("max_edges must be between 1 and " + MAX_EDGES);
    }
    if (maxResults < 1 || maxResults > MAX_RESULTS) {
      throw new IllegalArgumentException("max_results must be between 1 and " + MAX_RESULTS);
    }
  }

  private static boolean matchesTable(Set<String> tables, String query) {
    String normalized = query.toLowerCase(Locale.ROOT);
    return tables.stream()
        .anyMatch(
            table -> {
              String value = table.toLowerCase(Locale.ROOT);
              return value.equals(normalized) || value.endsWith("." + normalized);
            });
  }

  private static String kind(String name) {
    String lower = name.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".hpl")) return "pipeline";
    if (lower.endsWith(".hwf")) return "workflow";
    return "definition";
  }

  private static String safe(String value) {
    return SensitiveData.redactText(value == null ? "" : value);
  }

  private record Selector(
      String table, String metadata, String metadataType, String metadataName, String definition) {
    private static Selector table(String value) {
      return new Selector(value, null, null, null, null);
    }

    private static Selector metadata(String value) {
      int separator = value.indexOf(':');
      if (separator >= 0) {
        String type = value.substring(0, separator).trim();
        String name = value.substring(separator + 1).trim();
        if (separator == 0
            || separator == value.length() - 1
            || value.lastIndexOf(':') != separator
            || type.isEmpty()
            || name.isEmpty()) {
          throw new IllegalArgumentException("metadata must be NAME or TYPE:NAME");
        }
        return new Selector(null, value, type, name, null);
      }
      return new Selector(null, value, null, value, null);
    }

    private static Selector definition(String value) {
      return new Selector(null, null, null, null, value);
    }

    private boolean isTable() {
      return table != null;
    }

    private boolean isMetadata() {
      return metadata != null;
    }

    private boolean isDefinition() {
      return definition != null;
    }

    private Map<String, Object> output() {
      Map<String, Object> result = new LinkedHashMap<>();
      if (isTable()) result.put("table", safe(table));
      if (isMetadata()) result.put("metadata", safe(metadata));
      if (isDefinition()) result.put("definition", definition);
      return result;
    }
  }

  private record QueueItem(String path, int depth) {}

  private record Traversal(Map<String, Integer> nodes, boolean truncated) {}

  private record EdgeOutput(List<Map<String, Object>> edges, boolean truncated) {}
}
