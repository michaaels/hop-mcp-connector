package io.github.michaaels.hop.mcp;

import java.io.ByteArrayInputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
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
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.pipeline.PipelineHopMeta;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.apache.hop.workflow.WorkflowHopMeta;
import org.apache.hop.workflow.WorkflowMeta;
import org.apache.hop.workflow.action.ActionMeta;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Read-only semantic comparison of two native Hop definitions. */
final class HopDefinitionDiffService {
  static final int MAX_PATH_LENGTH = 4096;
  static final int MAX_COMPONENTS = ProjectFiles.MAX_STRUCTURED_RESULTS;
  static final int MAX_HOPS = ProjectFiles.MAX_STRUCTURED_RESULTS;
  static final int MAX_PARAMETERS = ProjectFiles.MAX_STRUCTURED_RESULTS;
  static final int MAX_REFERENCES = ProjectFiles.MAX_STRUCTURED_RESULTS;
  static final int MAX_PROPERTIES = 200;
  static final int MAX_COMPONENT_XML_BYTES = 512 * 1024;

  private final ProjectFiles files;
  private final IVariables variables;
  private final IHopMetadataProvider metadataProvider;

  HopDefinitionDiffService(
      ProjectFiles files, IVariables variables, IHopMetadataProvider metadataProvider) {
    this.files = Objects.requireNonNull(files, "files");
    this.variables = variables;
    this.metadataProvider = metadataProvider;
  }

  Map<String, Object> compare(String pathA, String pathB) throws Exception {
    if (variables == null || metadataProvider == null) {
      throw new IllegalStateException("Apache Hop metadata context is not initialized");
    }
    PathPair paths = validatePaths(pathA, pathB);
    Snapshot first = load(paths.first(), paths.kind());
    Snapshot second = load(paths.second(), paths.kind());

    Map<String, Component> firstComponents = componentsByKey(first.components());
    Map<String, Component> secondComponents = componentsByKey(second.components());
    List<String> addedComponents = new ArrayList<>();
    List<String> removedComponents = new ArrayList<>();
    List<Map<String, Object>> changedComponents = new ArrayList<>();
    for (String key : sortedKeys(secondComponents)) {
      if (!firstComponents.containsKey(key)) {
        addedComponents.add(safeName(secondComponents.get(key).name()));
      }
    }
    for (String key : sortedKeys(firstComponents)) {
      if (!secondComponents.containsKey(key)) {
        removedComponents.add(safeName(firstComponents.get(key).name()));
      }
    }
    for (String key : sortedIntersection(firstComponents, secondComponents)) {
      Component before = firstComponents.get(key);
      Component after = secondComponents.get(key);
      List<String> properties = changedProperties(before, after);
      if (!properties.isEmpty()) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("name", safeName(after.name()));
        change.put("kind", after.kind());
        change.put("properties", properties);
        change.put("properties_truncated", properties.size() >= MAX_PROPERTIES);
        changedComponents.add(change);
      }
    }

    HopComparison hopComparison = compareHops(first.hops(), second.hops(), paths.kind());
    ParameterComparison parameterComparison = compareParameters(first.parameters(), second.parameters());
    List<String> changedMetadataReferences =
        changedMetadataReferences(first.metadataReferences(), second.metadataReferences());
    Set<String> addedReferences = difference(second.references(), first.references());
    Set<String> removedReferences = difference(first.references(), second.references());
    List<String> definitionProperties = new ArrayList<>();
    if (!Objects.equals(first.name(), second.name())) definitionProperties.add("name");
    if (!Objects.equals(first.description(), second.description())) {
      definitionProperties.add("description");
    }

    boolean truncated =
        first.truncated()
            || second.truncated()
            || hopComparison.truncated()
            || parameterComparison.truncated();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("path_a", paths.firstRelative());
    result.put("path_b", paths.secondRelative());
    result.put("kind", paths.kind());
    result.put("identical", addedComponents.isEmpty()
        && removedComponents.isEmpty()
        && changedComponents.isEmpty()
        && hopComparison.added().isEmpty()
        && hopComparison.removed().isEmpty()
        && hopComparison.changed().isEmpty()
        && parameterComparison.changed().isEmpty()
        && changedMetadataReferences.isEmpty()
        && definitionProperties.isEmpty()
        && addedReferences.isEmpty()
        && removedReferences.isEmpty());
    result.put("definition_changed", Map.of("properties", definitionProperties));
    result.put("components_added", addedComponents);
    result.put("components_removed", removedComponents);
    result.put("components_changed", changedComponents);
    result.put("hops_added", hopComparison.added());
    result.put("hops_removed", hopComparison.removed());
    result.put("hops_changed", hopComparison.changed());
    result.put("parameters_changed", parameterComparison.changed());
    result.put("metadata_references_changed", changedMetadataReferences);
    result.put("metadata_references_added", safeReferences(addedReferences));
    result.put("metadata_references_removed", safeReferences(removedReferences));
    result.put("redaction_applied", true);
    result.put("truncated", truncated);
    result.put("comparison_complete", !truncated);
    return result;
  }

  private Snapshot load(java.nio.file.Path path, String kind) throws Exception {
    byte[] content = files.readBytes(path);
    String xml = decode(content);
    HopXml.parse(xml);
    if ("pipeline".equals(kind)) {
      PipelineMeta meta =
          new PipelineMeta(new ByteArrayInputStream(content), metadataProvider, variables);
      return snapshot(
          kind,
          meta.getName(),
          meta.getDescription(),
          pipelineComponents(meta),
          pipelineHops(meta),
          xml);
    }
    WorkflowMeta meta =
        new WorkflowMeta(new ByteArrayInputStream(content), metadataProvider, variables);
    return snapshot(
        kind,
        meta.getName(),
        meta.getDescription(),
        workflowComponents(meta),
        workflowHops(meta),
        xml);
  }

  private Snapshot snapshot(
      String kind,
      String name,
      String description,
      List<Component> components,
      List<Hop> hops,
      String referenceXml)
      throws Exception {
    ParameterResult parameters = parameters(referenceXml);
    ReferenceResult references = references(referenceXml);
    boolean structureTruncated =
        components.stream().anyMatch(Component::truncation)
            || hops.stream().anyMatch(Hop::truncation);
    return new Snapshot(
        kind,
        redactedScalar(name),
        redactedScalar(description),
        components,
        hops,
        parameters.values(),
        references.values(),
        metadataReferences(components),
        structureTruncated || parameters.truncated() || references.truncated());
  }

  private List<Component> pipelineComponents(PipelineMeta meta) throws Exception {
    List<TransformMeta> transforms = meta.getTransforms();
    List<Component> components = new ArrayList<>();
    int count = transforms == null ? 0 : transforms.size();
    for (int i = 0; i < Math.min(count, MAX_COMPONENTS); i++) {
      TransformMeta transform = transforms.get(i);
      components.add(
          new Component(
              transform.getName(),
              "transform",
              transform.getPluginId(),
              componentProperties(transform.getXml(), transform.getName())));
    }
    if (count > MAX_COMPONENTS) components.add(Component.truncationMarker());
    return components;
  }

  private List<Component> workflowComponents(WorkflowMeta meta) throws Exception {
    List<ActionMeta> actions = meta.getActions();
    List<Component> components = new ArrayList<>();
    int count = actions == null ? 0 : actions.size();
    for (int i = 0; i < Math.min(count, MAX_COMPONENTS); i++) {
      ActionMeta action = actions.get(i);
      String pluginId = action.getAction() == null ? "" : action.getAction().getPluginId();
      components.add(
          new Component(
              action.getName(),
              "action",
              pluginId,
              componentProperties(action.getXml(), action.getName())));
    }
    if (count > MAX_COMPONENTS) components.add(Component.truncationMarker());
    return components;
  }

  private List<Hop> pipelineHops(PipelineMeta meta) {
    List<PipelineHopMeta> nativeHops = meta.getPipelineHops();
    List<Hop> hops = new ArrayList<>();
    int count = nativeHops == null ? 0 : nativeHops.size();
    for (int i = 0; i < Math.min(count, MAX_HOPS); i++) {
      PipelineHopMeta hop = nativeHops.get(i);
      Map<String, Boolean> properties = new LinkedHashMap<>();
      properties.put("enabled", hop.isEnabled());
      properties.put("split", hop.isSplit());
      properties.put("error_hop", hop.isErrorHop());
      hops.add(
          new Hop(
              hop.getFromTransform().getName(), hop.getToTransform().getName(), properties));
    }
    if (count > MAX_HOPS) hops.add(Hop.truncationMarker());
    return hops;
  }

  private List<Hop> workflowHops(WorkflowMeta meta) {
    List<WorkflowHopMeta> nativeHops = meta.getWorkflowHops();
    List<Hop> hops = new ArrayList<>();
    int count = nativeHops == null ? 0 : nativeHops.size();
    for (int i = 0; i < Math.min(count, MAX_HOPS); i++) {
      WorkflowHopMeta hop = nativeHops.get(i);
      Map<String, Boolean> properties = new LinkedHashMap<>();
      properties.put("enabled", hop.isEnabled());
      properties.put("evaluation", hop.isEvaluation());
      properties.put("unconditional", hop.isUnconditional());
      hops.add(new Hop(hop.getFromAction().getName(), hop.getToAction().getName(), properties));
    }
    if (count > MAX_HOPS) hops.add(Hop.truncationMarker());
    return hops;
  }

  private Map<String, Object> componentProperties(String componentXml, String name) throws Exception {
    byte[] bytes = componentXml.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_COMPONENT_XML_BYTES) {
      throw new IllegalArgumentException("Component serialization exceeds the comparison limit");
    }
    String cleanXml = stripXmlDeclaration(componentXml);
    Map<String, Object> inspection =
        HopXml.component("", "<definition>" + cleanXml + "</definition>", name);
    return canonicalize(inspection.get("config"));
  }

  private static Map<String, Object> canonicalize(Object value) {
    if (value instanceof Map<?, ?> source) {
      Map<String, Object> result = new LinkedHashMap<>();
      source.keySet().stream()
          .map(String::valueOf)
          .filter(key -> !ignoredProperty(key))
          .sorted()
          .forEach(key -> result.put(key, canonicalize(source.get(key))));
      return result;
    }
    if (value instanceof List<?> source) {
      List<Object> result = new ArrayList<>();
      for (Object item : source) result.add(canonicalize(item));
      return Map.of("values", result);
    }
    if (value == null) return Map.of();
    return Map.of("value", SensitiveData.redactSensitiveText(String.valueOf(value)));
  }

  private static boolean ignoredProperty(String key) {
    String normalized = key.toLowerCase(Locale.ROOT);
    return normalized.equals("name")
        || normalized.equals("type")
        || normalized.equals("xloc")
        || normalized.equals("yloc")
        || normalized.equals("gui");
  }

  private static String stripXmlDeclaration(String xml) {
    String trimmed = xml == null ? "" : xml.trim();
    if (trimmed.startsWith("<?xml")) {
      int end = trimmed.indexOf("?>");
      if (end >= 0) return trimmed.substring(end + 2).trim();
    }
    return trimmed;
  }

  private static List<String> changedProperties(Component before, Component after) {
    TreeSet<String> changed = new TreeSet<>();
    if (!Objects.equals(before.kind(), after.kind())) changed.add("kind");
    if (!Objects.equals(before.pluginId(), after.pluginId())) changed.add("plugin_id");
    Map<String, String> beforeValues = flatten(before.properties());
    Map<String, String> afterValues = flatten(after.properties());
    TreeSet<String> keys = new TreeSet<>(beforeValues.keySet());
    keys.addAll(afterValues.keySet());
    for (String key : keys) {
      if (!Objects.equals(beforeValues.get(key), afterValues.get(key))) changed.add(key);
      if (changed.size() >= MAX_PROPERTIES) break;
    }
    return new ArrayList<>(changed);
  }

  private static Map<String, String> flatten(Map<String, Object> value) {
    Map<String, String> result = new LinkedHashMap<>();
    flatten(value, "", result, new int[] {0});
    return result;
  }

  private static void flatten(Object value, String path, Map<String, String> out, int[] count) {
    if (count[0] >= MAX_PROPERTIES) return;
    if (value instanceof Map<?, ?> map) {
      if (map.isEmpty()) {
        out.put(path, "{}");
        count[0]++;
        return;
      }
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        String child = path.isEmpty() ? String.valueOf(entry.getKey()) : path + "." + entry.getKey();
        flatten(entry.getValue(), child, out, count);
        if (count[0] >= MAX_PROPERTIES) return;
      }
      return;
    }
    if (value instanceof List<?> list) {
      if (list.isEmpty()) {
        out.put(path, "[]");
        count[0]++;
        return;
      }
      for (int i = 0; i < list.size(); i++) {
        flatten(list.get(i), path + "[" + i + "]", out, count);
        if (count[0] >= MAX_PROPERTIES) return;
      }
      return;
    }
    out.put(path, String.valueOf(value));
    count[0]++;
  }

  private static HopComparison compareHops(List<Hop> before, List<Hop> after, String kind) {
    Map<String, Hop> first = hopsByKey(before);
    Map<String, Hop> second = hopsByKey(after);
    List<Map<String, Object>> added = new ArrayList<>();
    List<Map<String, Object>> removed = new ArrayList<>();
    List<Map<String, Object>> changed = new ArrayList<>();
    for (String key : sortedKeys(second)) {
      if (!first.containsKey(key)) added.add(hopOutput(second.get(key), kind));
    }
    for (String key : sortedKeys(first)) {
      if (!second.containsKey(key)) removed.add(hopOutput(first.get(key), kind));
    }
    for (String key : sortedIntersection(first, second)) {
      Hop oldHop = first.get(key);
      Hop newHop = second.get(key);
      List<String> properties = new ArrayList<>();
      TreeSet<String> propertyNames = new TreeSet<>(oldHop.properties().keySet());
      propertyNames.addAll(newHop.properties().keySet());
      for (String property : propertyNames) {
        if (!Objects.equals(oldHop.properties().get(property), newHop.properties().get(property))) {
          properties.add(property);
        }
      }
      if (!properties.isEmpty()) {
        changed.add(
            Map.of(
                "from", safeName(newHop.from()),
                "to", safeName(newHop.to()),
                "properties", properties));
      }
    }
    return new HopComparison(
        added,
        removed,
        changed,
        before.stream().anyMatch(Hop::truncation) || after.stream().anyMatch(Hop::truncation));
  }

  private static Map<String, Object> hopOutput(Hop hop, String kind) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("from", safeName(hop.from()));
    result.put("to", safeName(hop.to()));
    result.put("kind", kind);
    result.put("properties", hop.properties());
    return result;
  }

  private static ParameterComparison compareParameters(
      Map<String, Map<String, String>> before, Map<String, Map<String, String>> after) {
    List<Map<String, Object>> changed = new ArrayList<>();
    TreeSet<String> names = new TreeSet<>(before.keySet());
    names.addAll(after.keySet());
    for (String name : names) {
      if (name.equals("__TRUNCATED__")) continue;
      Map<String, String> oldValues = before.getOrDefault(name, Map.of());
      Map<String, String> newValues = after.getOrDefault(name, Map.of());
      TreeSet<String> properties = new TreeSet<>(oldValues.keySet());
      properties.addAll(newValues.keySet());
      List<String> changedProperties = new ArrayList<>();
      for (String property : properties) {
        if (!Objects.equals(oldValues.get(property), newValues.get(property))) {
          changedProperties.add(property);
        }
      }
      if (!changedProperties.isEmpty()) {
        changed.add(Map.of("name", safeName(name), "properties", changedProperties));
      }
    }
    boolean truncated = before.containsKey("__TRUNCATED__") || after.containsKey("__TRUNCATED__");
    return new ParameterComparison(changed, truncated);
  }

  private static Map<String, String> metadataReferences(List<Component> components) {
    Map<String, String> result = new LinkedHashMap<>();
    for (Component component : components) {
      if (component.truncation()) continue;
      Map<String, String> properties = flatten(component.properties());
      for (Map.Entry<String, String> property : properties.entrySet()) {
        if (isMetadataReference(property.getKey())) {
          result.put(component.name() + "." + property.getKey(), property.getValue());
        }
      }
    }
    return result;
  }

  private static boolean isMetadataReference(String path) {
    String normalized = path.toLowerCase(Locale.ROOT);
    return normalized.contains("connection")
        || normalized.contains("metadata")
        || normalized.contains("run_configuration")
        || normalized.contains("runconfiguration")
        || normalized.contains("execution_information_location")
        || normalized.contains("execution_data_profile");
  }

  private static List<String> changedMetadataReferences(
      Map<String, String> before, Map<String, String> after) {
    TreeSet<String> keys = new TreeSet<>(before.keySet());
    keys.addAll(after.keySet());
    List<String> changed = new ArrayList<>();
    for (String key : keys) {
      if (!Objects.equals(before.get(key), after.get(key))) {
        changed.add(safeName(key));
        if (changed.size() >= MAX_PROPERTIES) break;
      }
    }
    return changed;
  }

  private static ParameterResult parameters(String xml) throws Exception {
    Document document = HopXml.parse(xml);
    NodeList nodes = document.getDocumentElement().getElementsByTagName("parameter");
    Map<String, Map<String, String>> values = new LinkedHashMap<>();
    boolean truncated = nodes.getLength() > MAX_PARAMETERS;
    int count = Math.min(nodes.getLength(), MAX_PARAMETERS);
    for (int i = 0; i < count; i++) {
      Element parameter = (Element) nodes.item(i);
      String name = childText(parameter, "name");
      if (name.isBlank()) name = parameter.getAttribute("name");
      if (name.isBlank()) continue;
      Map<String, String> properties = new LinkedHashMap<>();
      NodeList children = parameter.getChildNodes();
      for (int j = 0; j < children.getLength() && properties.size() < MAX_PROPERTIES; j++) {
        Node child = children.item(j);
        if (child.getNodeType() != Node.ELEMENT_NODE) continue;
        Element element = (Element) child;
        String key = element.getTagName();
        if (key.equalsIgnoreCase("name")) continue;
        String value = element.getTextContent() == null ? "" : element.getTextContent().trim();
        properties.put(key, SensitiveData.redactSensitiveText(value));
      }
      values.put(name, properties);
    }
    if (truncated) values.put("__TRUNCATED__", Map.of());
    return new ParameterResult(values, truncated);
  }

  private static ReferenceResult references(String xml) {
    Set<String> found = HopXml.references(xml, MAX_REFERENCES + 1);
    boolean truncated = found.size() > MAX_REFERENCES;
    Set<String> values = new TreeSet<>();
    for (String value : found.stream().limit(MAX_REFERENCES).toList()) values.add(value);
    return new ReferenceResult(values, truncated);
  }

  private static String childText(Element parent, String tag) {
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child.getNodeType() == Node.ELEMENT_NODE && tag.equals(child.getNodeName())) {
        return child.getTextContent() == null ? "" : child.getTextContent().trim();
      }
    }
    return "";
  }

  private PathPair validatePaths(String pathA, String pathB) throws Exception {
    requirePath(pathA, "path_a");
    requirePath(pathB, "path_b");
    java.nio.file.Path first = files.resolve(pathA);
    java.nio.file.Path second = files.resolve(pathB);
    String firstKind = kind(first);
    String secondKind = kind(second);
    if (firstKind == null || secondKind == null || !firstKind.equals(secondKind)) {
      throw new IllegalArgumentException("path_a and path_b must be matching .hpl or .hwf definitions");
    }
    return new PathPair(
        first, second, firstKind, files.relative(first), files.relative(second));
  }

  private static void requirePath(String path, String field) {
    if (path == null || path.isBlank() || path.length() > MAX_PATH_LENGTH) {
      throw new IllegalArgumentException(field + " must be a non-empty project-relative path");
    }
  }

  private static String kind(java.nio.file.Path path) {
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    if (name.endsWith(".hpl")) return "pipeline";
    if (name.endsWith(".hwf")) return "workflow";
    return null;
  }

  private static String decode(byte[] content) throws CharacterCodingException {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(content))
        .toString();
  }

  private static String redactedScalar(String value) {
    return SensitiveData.redactSensitiveText(value == null ? "" : value);
  }

  private static String safeName(String value) {
    return clip(SensitiveData.redactText(value == null ? "" : value), 512);
  }

  private static List<String> safeReferences(Set<String> values) {
    List<String> result = new ArrayList<>();
    for (String value : values) result.add(clip(SensitiveData.redactText(value), 512));
    return result;
  }

  private static String clip(String value, int max) {
    if (value == null) return "";
    return value.length() <= max ? value : value.substring(0, max);
  }

  private static Map<String, Component> componentsByKey(List<Component> components) {
    Map<String, Component> result = new LinkedHashMap<>();
    for (Component component : components) {
      if (!component.truncation()) result.put(component.kind() + "\u0000" + component.name(), component);
    }
    return result;
  }

  private static Map<String, Hop> hopsByKey(List<Hop> hops) {
    Map<String, Hop> result = new LinkedHashMap<>();
    for (Hop hop : hops) {
      if (!hop.truncation()) result.put(hop.from() + "\u0000" + hop.to(), hop);
    }
    return result;
  }

  private static List<String> sortedKeys(Map<?, ?> values) {
    return values.keySet().stream().map(String::valueOf).sorted().toList();
  }

  private static List<String> sortedIntersection(Map<String, ?> first, Map<String, ?> second) {
    return first.keySet().stream().filter(second::containsKey).sorted().toList();
  }

  private static Set<String> difference(Set<String> first, Set<String> second) {
    Set<String> result = new TreeSet<>(first);
    result.removeAll(second);
    return result;
  }

  private record PathPair(
      java.nio.file.Path first,
      java.nio.file.Path second,
      String kind,
      String firstRelative,
      String secondRelative) {}

  private record Component(String name, String kind, String pluginId, Map<String, Object> properties) {
    private static Component truncationMarker() {
      return new Component("", "", "", Map.of());
    }

    private boolean truncation() {
      return name.isEmpty() && kind.isEmpty();
    }
  }

  private record Hop(String from, String to, Map<String, Boolean> properties) {
    private static Hop truncationMarker() {
      return new Hop("", "", Map.of());
    }

    private boolean truncation() {
      return from.isEmpty() && to.isEmpty();
    }
  }

  private record Snapshot(
      String kind,
      String name,
      String description,
      List<Component> components,
      List<Hop> hops,
      Map<String, Map<String, String>> parameters,
      Set<String> references,
      Map<String, String> metadataReferences,
      boolean truncated) {}

  private record ParameterResult(Map<String, Map<String, String>> values, boolean truncated) {}

  private record ReferenceResult(Set<String> values, boolean truncated) {}

  private record HopComparison(
      List<Map<String, Object>> added,
      List<Map<String, Object>> removed,
      List<Map<String, Object>> changed,
      boolean truncated) {}

  private record ParameterComparison(List<Map<String, Object>> changed, boolean truncated) {}
}
