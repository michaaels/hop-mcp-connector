package io.github.michaaels.hop.mcp;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

final class HopXml {
  private static final Pattern TABLE =
      Pattern.compile(
          "(?i)\\b(?:from|join|update|into|delete\\s+from|merge\\s+into)\\s+([`\"\\[\\]A-Za-z0-9_.$#-]+)");
  private static final Pattern REF = Pattern.compile("(?i)([^\\s\"'<>]+\\.(?:hpl|hwf))");
  private static final Set<String> SECRET_NAMES =
      Set.of(
          "password",
          "passwd",
          "pwd",
          "token",
          "secret",
          "client_secret",
          "api_key",
          "apikey",
          "access_key",
          "private_key");

  private HopXml() {}

  static Document parse(String xml) throws Exception {
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    f.setFeature("http://xml.org/sax/features/external-general-entities", false);
    f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    f.setXIncludeAware(false);
    f.setExpandEntityReferences(false);
    try {
      f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    } catch (Exception ignored) {
    }
    try {
      f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    } catch (Exception ignored) {
    }
    return f.newDocumentBuilder()
        .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }

  static Map<String, Object> inspect(String path, String xml) throws Exception {
    Document d = parse(xml);
    Element root = d.getDocumentElement();
    String type =
        root.getTagName().toLowerCase(Locale.ROOT).contains("workflow") ? "workflow" : "pipeline";
    String componentTag = type.equals("pipeline") ? "transform" : "action";
    List<Map<String, Object>> components = new ArrayList<>();
    NodeList cs = root.getElementsByTagName(componentTag);
    for (int i = 0; i < cs.getLength() && components.size() < ProjectFiles.MAX_RESULTS; i++) {
      Element e = (Element) cs.item(i);
      String name = childText(e, "name");
      if (name == null || name.isBlank()) continue;
      String plugin =
          firstNonBlank(childText(e, "type"), childText(e, "plugin_id"), childText(e, "pluginId"));
      Map<String, Object> c = new LinkedHashMap<>();
      c.put("name", name);
      if (plugin != null) c.put("plugin", plugin);
      c.put("tag", componentTag);
      components.add(c);
    }
    NodeList hopNodes = root.getElementsByTagName("hop");
    List<Map<String, Object>> hops = hops(root, ProjectFiles.MAX_RESULTS);
    String searchable = searchableText(root);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("path", path);
    out.put("type", type);
    out.put("name", definitionName(root, path));
    out.put("components", components);
    out.put("hops", hops);
    out.put("components_truncated", cs.getLength() > ProjectFiles.MAX_RESULTS);
    out.put("hops_truncated", hopNodes.getLength() > ProjectFiles.MAX_RESULTS);
    out.put("component_count", components.size());
    out.put("hop_count", hops.size());
    out.put("tables", findTables(searchable));
    out.put("references", references(searchable));
    return out;
  }

  static Map<String, Object> component(String path, String xml, String wanted) throws Exception {
    if (wanted == null || wanted.isBlank())
      throw new IllegalArgumentException("component is required");
    Document d = parse(xml);
    Element root = d.getDocumentElement();
    for (String tag : List.of("transform", "action")) {
      NodeList list = root.getElementsByTagName(tag);
      for (int i = 0; i < list.getLength(); i++) {
        Element e = (Element) list.item(i);
        if (wanted.equals(childText(e, "name"))) {
          String searchable = searchableText(e);
          Map<String, Object> out = new LinkedHashMap<>();
          out.put("path", path);
          out.put("name", wanted);
          out.put("kind", tag);
          out.put("config", elementMap(e));
          out.put("tables", findTables(searchable));
          out.put("references", references(searchable));
          return out;
        }
      }
    }
    throw new IllegalArgumentException("Component not found: " + wanted);
  }

  static Map<String, Object> validate(String path, String xml) throws Exception {
    Document d = parse(xml);
    Element root = d.getDocumentElement();
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    boolean diagnosticsTruncated = false;
    String type =
        root.getTagName().toLowerCase(Locale.ROOT).contains("workflow") ? "workflow" : "pipeline";
    String tag = type.equals("pipeline") ? "transform" : "action";
    Set<String> names = new HashSet<>();
    NodeList list = root.getElementsByTagName(tag);
    for (int i = 0; i < list.getLength(); i++) {
      String n = childText((Element) list.item(i), "name");
      if (n == null || n.isBlank()) {
        if (warnings.size() < ProjectFiles.MAX_RESULTS) warnings.add(tag + " without name");
        else diagnosticsTruncated = true;
      } else if (!names.add(n)) {
        if (errors.size() < ProjectFiles.MAX_RESULTS) errors.add("Duplicate component name: " + n);
        else diagnosticsTruncated = true;
      }
    }
    for (Map<String, Object> h : hops(root)) {
      String from = String.valueOf(h.getOrDefault("from", ""));
      String to = String.valueOf(h.getOrDefault("to", ""));
      if (!names.contains(from)) {
        if (errors.size() < ProjectFiles.MAX_RESULTS) errors.add("Hop source not found: " + from);
        else diagnosticsTruncated = true;
      }
      if (!names.contains(to)) {
        if (errors.size() < ProjectFiles.MAX_RESULTS) errors.add("Hop target not found: " + to);
        else diagnosticsTruncated = true;
      }
    }
    return Map.of(
        "path",
        path,
        "type",
        type,
        "valid",
        errors.isEmpty(),
        "errors",
        errors,
        "warnings",
        warnings,
        "diagnostics_truncated",
        diagnosticsTruncated);
  }

  static List<Map<String, Object>> lineage(String xml, String start, String direction, int maxDepth)
      throws Exception {
    Document d = parse(xml);
    List<Map<String, Object>> hs = hops(d.getDocumentElement());
    if (start == null || start.isBlank())
      throw new IllegalArgumentException("component is required");
    boolean upstream = "upstream".equalsIgnoreCase(direction);
    List<Map<String, Object>> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    ArrayDeque<Map.Entry<String, Integer>> q = new ArrayDeque<>();
    q.add(Map.entry(start, 0));
    seen.add(start);
    int depth = Math.max(1, Math.min(maxDepth, 50));
    while (!q.isEmpty()) {
      var cur = q.remove();
      if (cur.getValue() >= depth) continue;
      for (Map<String, Object> h : hs) {
        String from = String.valueOf(h.get("from"));
        String to = String.valueOf(h.get("to"));
        boolean match = upstream ? to.equals(cur.getKey()) : from.equals(cur.getKey());
        if (!match) continue;
        String next = upstream ? from : to;
        Map<String, Object> row = new LinkedHashMap<>(h);
        row.put("depth", cur.getValue() + 1);
        result.add(row);
        if (seen.add(next)) q.add(Map.entry(next, cur.getValue() + 1));
      }
    }
    return result;
  }

  static Set<String> findTables(String text) {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    Matcher m = TABLE.matcher(text == null ? "" : text);
    while (m.find() && out.size() < ProjectFiles.MAX_RESULTS) out.add(cleanIdentifier(m.group(1)));
    return out;
  }

  static Set<String> references(String text) {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    Matcher m = REF.matcher(text == null ? "" : text);
    while (m.find() && out.size() < ProjectFiles.MAX_RESULTS) out.add(m.group(1));
    return out;
  }

  static String redact(String text) {
    if (text == null || text.isBlank()) return text;
    String redacted =
        text.replaceAll(
            "(?i)(authorization\\s*[:=]\\s*(?:bearer|basic)\\s+)[^\\s,;]+", "$1***REDACTED***");
    return redacted.replaceAll(
        "(?i)([\\\"']?(?:password|passwd|pwd|token|secret|client[_-]?secret|api[_-]?key|apikey|access[_-]?key|private[_-]?key)[\\\"']?\\s*[=:]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;}\\]]+)",
        "$1\"***REDACTED***\"");
  }

  private static List<Map<String, Object>> hops(Element root) {
    return hops(root, Integer.MAX_VALUE);
  }

  private static List<Map<String, Object>> hops(Element root, int maxItems) {
    List<Map<String, Object>> out = new ArrayList<>();
    NodeList list = root.getElementsByTagName("hop");
    for (int i = 0; i < list.getLength() && out.size() < maxItems; i++) {
      Element e = (Element) list.item(i);
      String from = childText(e, "from");
      String to = childText(e, "to");
      if (from == null || to == null) continue;
      Map<String, Object> h = new LinkedHashMap<>();
      h.put("from", from);
      h.put("to", to);
      String enabled = childText(e, "enabled");
      if (enabled != null)
        h.put("enabled", !enabled.equalsIgnoreCase("N") && !enabled.equalsIgnoreCase("false"));
      out.add(h);
    }
    return out;
  }

  private static Map<String, Object> elementMap(Element e) {
    Map<String, Object> out = new LinkedHashMap<>();
    Map<String, List<Object>> tmp = new LinkedHashMap<>();
    NodeList children = e.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node n = children.item(i);
      if (n.getNodeType() != Node.ELEMENT_NODE) continue;
      Element c = (Element) n;
      String key = c.getTagName();
      Object value;
      if (isSecretName(key)) value = "***REDACTED***";
      else if (hasElementChildren(c)) value = elementMap(c);
      else
        value =
            ProjectFiles.truncate(
                c.getTextContent() == null ? "" : c.getTextContent().trim(), 4000);
      tmp.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
    }
    for (var en : tmp.entrySet()) {
      out.put(en.getKey(), en.getValue().size() == 1 ? en.getValue().get(0) : en.getValue());
    }
    return out;
  }

  private static String searchableText(Element e) {
    StringBuilder out = new StringBuilder();
    appendSearchableText(e, out);
    return out.toString();
  }

  private static void appendSearchableText(Node node, StringBuilder out) {
    if (node.getNodeType() == Node.ELEMENT_NODE && isSecretName(((Element) node).getTagName()))
      return;
    NodeList children = node.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child.getNodeType() == Node.ELEMENT_NODE) {
        appendSearchableText(child, out);
      } else if (child.getNodeType() == Node.TEXT_NODE
          || child.getNodeType() == Node.CDATA_SECTION_NODE) {
        String value = child.getNodeValue();
        if (value != null && !value.isBlank()) out.append(value).append('\n');
      }
    }
  }

  private static boolean hasElementChildren(Element e) {
    NodeList n = e.getChildNodes();
    for (int i = 0; i < n.getLength(); i++)
      if (n.item(i).getNodeType() == Node.ELEMENT_NODE) return true;
    return false;
  }

  private static boolean isSecretName(String key) {
    String s = key.toLowerCase(Locale.ROOT);
    return SECRET_NAMES.contains(s)
        || s.contains("password")
        || s.endsWith("token")
        || s.endsWith("secret")
        || s.contains("api_key")
        || s.contains("apikey")
        || s.contains("access_key")
        || s.contains("private_key");
  }

  private static String definitionName(Element root, String path) {
    String n = childText(root, "name");
    if (n != null && !n.isBlank()) return n;
    NodeList info = root.getElementsByTagName("info");
    if (info.getLength() > 0) {
      n = childText((Element) info.item(0), "name");
      if (n != null && !n.isBlank()) return n;
    }
    int slash = path.lastIndexOf('/');
    String f = slash >= 0 ? path.substring(slash + 1) : path;
    int dot = f.lastIndexOf('.');
    return dot > 0 ? f.substring(0, dot) : f;
  }

  private static String childText(Element e, String tag) {
    NodeList n = e.getChildNodes();
    for (int i = 0; i < n.getLength(); i++) {
      Node x = n.item(i);
      if (x.getNodeType() == Node.ELEMENT_NODE && ((Element) x).getTagName().equals(tag)) {
        return x.getTextContent() == null ? null : x.getTextContent().trim();
      }
    }
    return null;
  }

  private static String firstNonBlank(String... xs) {
    for (String x : xs) if (x != null && !x.isBlank()) return x;
    return null;
  }

  private static String cleanIdentifier(String x) {
    return x.replace("`", "")
        .replace("\"", "")
        .replace("[", "")
        .replace("]", "")
        .replaceAll("[;,)]+$", "");
  }
}
