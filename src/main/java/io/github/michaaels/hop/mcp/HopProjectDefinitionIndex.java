package io.github.michaaels.hop.mcp;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Incremental in-memory index of project pipeline/workflow definitions.
 *
 * <p>The index is intentionally project-local and bounded by the same scan/read budgets as
 * {@link ProjectFiles}. Unchanged definitions are reused across MCP calls based on size and last
 * modified time; changed definitions are reparsed individually.
 */
final class HopProjectDefinitionIndex {
  static final int MAX_COMPONENTS = ProjectFiles.MAX_STRUCTURED_RESULTS;
  static final int MAX_TABLES = 200;
  static final int MAX_REFERENCES = 200;

  record ComponentText(String name, String text) {}

  record Entry(
      String path,
      String kind,
      String xml,
      Map<String, Object> inspection,
      Set<String> tables,
      Set<String> references,
      List<ComponentText> components,
      long bytes,
      long size,
      long lastModifiedMillis,
      boolean tablesTruncated,
      boolean referencesTruncated,
      boolean componentsTruncated) {

    boolean containsText(String query) {
      return containsIgnoreCase(xml, query);
    }

    List<String> componentsContaining(String query, int maxItems) {
      if (query == null || query.isBlank() || maxItems < 1) return List.of();
      List<String> result = new ArrayList<>();
      for (ComponentText component : components) {
        if (containsIgnoreCase(component.text(), query)) {
          result.add(component.name());
          if (result.size() >= maxItems) break;
        }
      }
      return result;
    }
  }

  record Snapshot(
      Map<String, Entry> definitions,
      boolean truncated,
      long indexedBytes,
      int cacheHits,
      int cacheMisses) {}

  private final ProjectFiles files;
  private Map<String, Entry> cache = new LinkedHashMap<>();

  HopProjectDefinitionIndex(ProjectFiles files) {
    this.files = files;
  }

  synchronized Snapshot snapshot() throws Exception {
    BoundedProjectWalker.ScanResult scan = files.definitionScan(ProjectFiles.MAX_SCAN_FILES);
    List<Path> paths = new ArrayList<>(scan.files());
    paths.sort(Comparator.comparing(files::relative));

    Map<String, Entry> next = new LinkedHashMap<>();
    boolean truncated = scan.scanLimitReached() || scan.resultsTruncated();
    long indexedBytes = 0L;
    int cacheHits = 0;
    int cacheMisses = 0;

    for (Path path : paths) {
      long size;
      long lastModified;
      try {
        size = Files.size(path);
        lastModified = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
      } catch (Exception ignored) {
        truncated = true;
        continue;
      }
      if (size > ProjectFiles.MAX_READ_BYTES
          || size > ProjectFiles.MAX_TOTAL_SCAN_BYTES - indexedBytes) {
        truncated = true;
        continue;
      }

      String relative = files.relative(path);
      Entry cached = cache.get(relative);
      Entry entry;
      if (cached != null && cached.size() == size && cached.lastModifiedMillis() == lastModified) {
        entry = cached;
        cacheHits++;
      } else {
        try {
          entry = read(path, relative, size, lastModified);
          cacheMisses++;
        } catch (Exception ignored) {
          truncated = true;
          continue;
        }
      }
      indexedBytes += entry.bytes();
      next.put(relative, entry);
    }

    cache = next;
    return new Snapshot(
        Map.copyOf(next), truncated, indexedBytes, cacheHits, cacheMisses);
  }

  Entry readSingle(Path path) throws Exception {
    long size = Files.size(path);
    if (size > ProjectFiles.MAX_READ_BYTES) {
      throw new IllegalArgumentException("Definition exceeds the configured read limit");
    }
    return read(
        path,
        files.relative(path),
        size,
        Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis());
  }

  private Entry read(Path path, String relative, long size, long lastModified) throws Exception {
    byte[] content = files.readBytes(path);
    String xml =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(content))
            .toString();
    Document document = HopXml.parse(xml);
    Map<String, Object> inspection = HopXml.inspect(relative, document);
    Set<String> tables = HopXml.findTables(xml, null, MAX_TABLES + 1);
    Set<String> references = HopXml.references(xml, MAX_REFERENCES + 1);
    ComponentScan componentScan = components(document);
    return new Entry(
        relative,
        kind(relative),
        xml,
        inspection,
        Set.copyOf(tables),
        Set.copyOf(references),
        List.copyOf(componentScan.components()),
        content.length,
        size,
        lastModified,
        tables.size() > MAX_TABLES,
        references.size() > MAX_REFERENCES,
        componentScan.truncated());
  }

  private static ComponentScan components(Document document) {
    List<ComponentText> result = new ArrayList<>();
    boolean truncated = false;
    Element root = document.getDocumentElement();
    for (String tag : List.of("transform", "action")) {
      NodeList nodes = root.getElementsByTagName(tag);
      for (int i = 0; i < nodes.getLength(); i++) {
        if (result.size() >= MAX_COMPONENTS) {
          truncated = true;
          break;
        }
        Element element = (Element) nodes.item(i);
        String name = childText(element, "name");
        if (name == null || name.isBlank()) continue;
        String text = element.getTextContent();
        result.add(new ComponentText(name, text == null ? "" : text));
      }
      if (truncated) break;
    }
    return new ComponentScan(result, truncated);
  }

  private static String childText(Element element, String name) {
    NodeList nodes = element.getElementsByTagName(name);
    if (nodes.getLength() == 0) return null;
    Node node = nodes.item(0);
    return node == null || node.getTextContent() == null ? null : node.getTextContent().trim();
  }

  static boolean containsIgnoreCase(String value, String query) {
    if (value == null || query == null || query.isEmpty() || query.length() > value.length()) {
      return false;
    }
    int max = value.length() - query.length();
    for (int i = 0; i <= max; i++) {
      if (value.regionMatches(true, i, query, 0, query.length())) return true;
    }
    return false;
  }

  private static String kind(String path) {
    String lower = path.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".hpl")) return "pipeline";
    if (lower.endsWith(".hwf")) return "workflow";
    return "definition";
  }

  private record ComponentScan(List<ComponentText> components, boolean truncated) {}
}
