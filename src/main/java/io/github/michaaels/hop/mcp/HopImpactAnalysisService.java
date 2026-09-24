package io.github.michaaels.hop.mcp;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Bounded project-local impact graph built from Hop definitions and their references. */
final class HopImpactAnalysisService {
  static final int MAX_DEPTH = 64;
  static final int MAX_EDGES = 500;
  static final int MAX_RESULTS = ProjectFiles.MAX_STRUCTURED_RESULTS;
  static final int MAX_TABLE_REFERENCES = 200;
  static final int MAX_METADATA_REFERENCES = 200;
  static final int MAX_DEFINITION_REFERENCES = 200;
  static final int MAX_PATH_LENGTH = 4096;
  static final int MAX_SELECTOR_LENGTH = 1024;

  private final ProjectFiles files;

  HopImpactAnalysisService(ProjectFiles files) {
    this.files = Objects.requireNonNull(files, "files");
  }

  Map<String, Object> analyze(
      String table,
      String metadata,
      String definition,
      int maxDepth,
      int maxEdges,
      int maxResults)
      throws Exception {
    Selector selector = selector(table, metadata, definition);
    validateBounds(maxDepth, maxEdges, maxResults);
    if (selector.isDefinition()) {
      Path requested = files.resolve(selector.definition());
      selector = Selector.definition(files.relative(requested));
    }
    DefinitionLoad loaded = loadDefinitions();
    Map<String, Definition> definitions = loaded.definitions();
    if (selector.isDefinition() && !definitions.containsKey(selector.definition())) {
      Definition requestedDefinition = readDefinition(files.resolve(selector.definition()));
      definitions.put(requestedDefinition.path(), requestedDefinition);
    }
    Map<String, Set<String>> outbound = new LinkedHashMap<>();
    Map<String, Set<String>> inbound = new LinkedHashMap<>();
    List<Map<String, Object>> dependencyEdges = new ArrayList<>();
    boolean referenceTruncated = false;
    for (Definition current : definitions.values()) {
      referenceTruncated |= current.referencesTruncated() || current.tablesTruncated();
      for (String reference : current.references()) {
        Path resolved = resolveReference(current.path(), reference);
        if (resolved == null) continue;
        String target = files.relative(resolved);
        outbound.computeIfAbsent(current.path(), ignored -> new LinkedHashSet<>()).add(target);
        inbound.computeIfAbsent(target, ignored -> new LinkedHashSet<>()).add(current.path());
        if (dependencyEdges.size() < maxEdges) {
          Definition targetDefinition = definitions.get(target);
          Map<String, Object> edge = new LinkedHashMap<>();
          edge.put("from", current.path());
          edge.put("to", target);
          edge.put("type", "definition_reference");
          edge.put("from_kind", current.kind());
          edge.put("to_kind", targetDefinition == null ? kind(target) : targetDefinition.kind());
          dependencyEdges.add(edge);
        } else {
          referenceTruncated = true;
        }
      }
    }

    Set<String> initial = matchingDefinitions(definitions, selector);
    Traversal traversal =
        traverse(
            initial,
            selector.isDefinition() ? inbound : outbound,
            definitions,
            maxDepth,
            maxEdges,
            maxResults);
    List<Map<String, Object>> nodes = nodeOutput(traversal.nodes(), definitions);
    List<Map<String, Object>> tableReferences = tableReferences(selector, traversal.nodes(), definitions);
    List<Map<String, Object>> metadataReferences =
        metadataReferences(selector, traversal.nodes(), definitions);
    List<Map<String, Object>> lineage = lineage(traversal.nodes(), definitions, maxEdges);
    List<Map<String, Object>> pipelineWorkflowReferences =
        pipelineWorkflowReferences(traversal.nodes(), dependencyEdges);
    boolean truncated =
        referenceTruncated
            || loaded.truncated()
            || traversal.truncated()
            || tableReferences.size() >= MAX_TABLE_REFERENCES
            || metadataReferences.size() >= MAX_METADATA_REFERENCES
            || lineage.size() >= maxEdges;
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("query", selector.output());
    result.put("nodes", nodes);
    result.put("dependencies", bounded(dependencyEdges, maxEdges));
    result.put("metadata_references", metadataReferences);
    result.put("table_references", tableReferences);
    result.put("pipeline_workflow_references", pipelineWorkflowReferences);
    result.put("lineage", lineage);
    result.put("node_count", traversal.nodes().size());
    result.put("returned_nodes", nodes.size());
    result.put("edge_count", dependencyEdges.size());
    result.put("returned_edges", Math.min(dependencyEdges.size(), maxEdges));
    result.put("max_depth_applied", maxDepth);
    result.put("max_edges_applied", maxEdges);
    result.put("max_results_applied", maxResults);
    result.put("count_complete", !truncated);
    result.put("results_truncated", truncated);
    result.put("has_more", truncated);
    result.put("redaction_applied", true);
    return result;
  }

  private DefinitionLoad loadDefinitions() throws Exception {
    Map<String, Definition> result = new LinkedHashMap<>();
    boolean truncated = false;
    long scannedBytes = 0;
    var scan = files.definitionScan(ProjectFiles.MAX_SCAN_FILES);
    List<Path> paths = new ArrayList<>(scan.files());
    paths.sort(Comparator.comparing(files::relative));
    for (Path path : paths) {
      long size = Files.size(path);
      if (size > ProjectFiles.MAX_READ_BYTES
          || size > ProjectFiles.MAX_TOTAL_SCAN_BYTES - scannedBytes) {
        truncated = true;
        continue;
      }
      Definition definition = readDefinition(path);
      scannedBytes += definition.bytes();
      result.put(definition.path(), definition);
    }
    truncated |= scan.scanLimitReached() || scan.resultsTruncated();
    return new DefinitionLoad(result, truncated);
  }

  private Definition readDefinition(Path path) throws Exception {
    byte[] content = files.readBytes(path);
    String xml = decode(content);
    Map<String, Object> inspection = HopXml.inspect(files.relative(path), xml);
    Set<String> tables = HopXml.findTables(xml, null, MAX_TABLE_REFERENCES + 1);
    Set<String> references = HopXml.references(xml, MAX_DEFINITION_REFERENCES + 1);
    return new Definition(
        files.relative(path),
        kind(path.getFileName().toString()),
        xml,
        inspection,
        tables,
        references,
        content.length,
        tables.size() > MAX_TABLE_REFERENCES,
        references.size() > MAX_DEFINITION_REFERENCES);
  }

  private static Set<String> matchingDefinitions(
      Map<String, Definition> definitions, Selector selector) {
    Set<String> result = new LinkedHashSet<>();
    for (Definition definition : definitions.values()) {
      if (selector.isDefinition() && selector.definition().equals(definition.path())) {
        result.add(definition.path());
      } else if (selector.isTable() && matchesTable(definition.tables(), selector.table())) {
        result.add(definition.path());
      } else if (selector.isMetadata() && matchesMetadata(definition.xml(), selector.metadata())) {
        result.add(definition.path());
      }
    }
    return result;
  }

  private static Traversal traverse(
      Set<String> initial,
      Map<String, Set<String>> graph,
      Map<String, Definition> definitions,
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
      Map<String, Integer> nodes, Map<String, Definition> definitions) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map.Entry<String, Integer> node : nodes.entrySet()) {
      Definition definition = definitions.get(node.getKey());
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
      Selector selector, Map<String, Integer> nodes, Map<String, Definition> definitions) {
    if (!selector.isTable()) return List.of();
    List<Map<String, Object>> result = new ArrayList<>();
    for (String path : nodes.keySet()) {
      Definition definition = definitions.get(path);
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
      Selector selector, Map<String, Integer> nodes, Map<String, Definition> definitions) {
    if (!selector.isMetadata()) return List.of();
    List<Map<String, Object>> result = new ArrayList<>();
    for (String path : nodes.keySet()) {
      Definition definition = definitions.get(path);
      if (definition != null && matchesMetadata(definition.xml(), selector.metadata())) {
        result.add(Map.of("path", path, "metadata", safe(selector.metadata())));
        if (result.size() >= MAX_METADATA_REFERENCES) return result;
      }
    }
    return result;
  }

  private static List<Map<String, Object>> lineage(
      Map<String, Integer> nodes,
      Map<String, Definition> definitions,
      int maxEdges) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (String path : nodes.keySet()) {
      Definition definition = definitions.get(path);
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

  private static List<Map<String, Object>> pipelineWorkflowReferences(
      Map<String, Integer> nodes, List<Map<String, Object>> dependencies) {
    Set<String> included = nodes.keySet();
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, Object> dependency : dependencies) {
      if (included.contains(dependency.get("from")) || included.contains(dependency.get("to"))) {
        result.add(dependency);
      }
    }
    return result;
  }

  private static <T> List<T> bounded(List<T> values, int max) {
    return values.size() <= max ? values : new ArrayList<>(values.subList(0, max));
  }

  private Path resolveReference(String source, String reference) {
    try {
      if (reference == null || reference.isBlank() || reference.length() > MAX_PATH_LENGTH) return null;
      Path raw = Path.of(reference);
      Path candidate = raw.isAbsolute() ? raw : files.root().resolve(source).getParent().resolve(raw);
      candidate = candidate.normalize();
      if (!candidate.startsWith(files.root()) || !Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
        return null;
      }
      Path real = candidate.toRealPath();
      return real.startsWith(files.root()) ? real : null;
    } catch (Exception ignored) {
      return null;
    }
  }

  private static Selector selector(String table, String metadata, String definition) throws Exception {
    int count = count(table) + count(metadata) + count(definition);
    if (count != 1) throw new IllegalArgumentException("Exactly one of table, metadata or definition is required");
    if (count(table) == 1) return Selector.table(requireSelector(table, "table"));
    if (count(metadata) == 1) return Selector.metadata(requireSelector(metadata, "metadata"));
    String path = requireSelector(definition, "definition");
    return Selector.definition(path);
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
              return value.equals(normalized) || value.contains(normalized);
            });
  }

  private static boolean matchesMetadata(String xml, String query) {
    return xml.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
  }

  private static String decode(byte[] content) throws CharacterCodingException {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(content))
        .toString();
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

  private record Selector(String table, String metadata, String definition) {
    private static Selector table(String value) {
      return new Selector(value, null, null);
    }

    private static Selector metadata(String value) {
      return new Selector(null, value, null);
    }

    private static Selector definition(String value) {
      return new Selector(null, null, value);
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

  private record Definition(
      String path,
      String kind,
      String xml,
      Map<String, Object> inspection,
      Set<String> tables,
      Set<String> references,
      long bytes,
      boolean tablesTruncated,
      boolean referencesTruncated) {}

  private record DefinitionLoad(Map<String, Definition> definitions, boolean truncated) {}

  private record QueueItem(String path, int depth) {}

  private record Traversal(Map<String, Integer> nodes, boolean truncated) {}
}
