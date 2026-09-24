package io.github.michaaels.hop.mcp;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.core.variables.Variables;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Bounded, read-only comparison of non-sensitive definition environments. */
final class HopEnvironmentDiffService {
  static final int MAX_PATH_LENGTH = 4096;
  static final int MAX_NAME_LENGTH = 512;
  static final int MAX_PARAMETERS = 100;
  static final int MAX_VARIABLES = 200;
  static final int MAX_METADATA_REFERENCES = 200;
  static final int MAX_CHANGES = 200;
  static final int MAX_VALUE_LENGTH = 1024;

  private static final Pattern VARIABLE_REFERENCE = Pattern.compile("\\$\\{([^}]{1,256})}");

  private final ProjectFiles files;
  private final IVariables variables;
  private final HopRunConfigurationService runConfigurations;

  HopEnvironmentDiffService(
      ProjectFiles files, IVariables variables, HopRunConfigurationService runConfigurations) {
    this.files = Objects.requireNonNull(files, "files");
    this.variables = variables;
    this.runConfigurations = Objects.requireNonNull(runConfigurations, "runConfigurations");
  }

  Map<String, Object> compare(
      String pathA,
      String pathB,
      String runConfigurationA,
      String runConfigurationB,
      Map<String, String> parametersA,
      Map<String, String> parametersB)
      throws Exception {
    PathPair paths = validatePaths(pathA, pathB);
    Map<String, String> safeParametersA = validateParameters(parametersA, "parameters_a");
    Map<String, String> safeParametersB = validateParameters(parametersB, "parameters_b");
    String configA = defaultConfiguration(runConfigurationA);
    String configB = defaultConfiguration(runConfigurationB);

    Profile first = load(paths.first(), paths.firstRelative(), configA, safeParametersA);
    Profile second = load(paths.second(), paths.secondRelative(), configB, safeParametersB);

    List<Map<String, Object>> variablesChanged =
        compareValues("name", mergeVariables(first), mergeVariables(second));
    List<Map<String, Object>> metadataChanged =
        compareValues("path", first.definition().metadata(), second.definition().metadata());
    List<Map<String, Object>> runConfigurationChanged =
        compareValues("property", configurationValues(first), configurationValues(second));
    List<Map<String, Object>> parameterDefaultsChanged =
        compareParameterDefaults(first.definition().parameters(), second.definition().parameters());

    List<Map<String, Object>> unresolvedVariables = unresolved(first, second);
    boolean truncated =
        first.truncated()
            || second.truncated()
            || variablesChanged.size() >= MAX_CHANGES
            || metadataChanged.size() >= MAX_CHANGES
            || runConfigurationChanged.size() >= MAX_CHANGES
            || parameterDefaultsChanged.size() >= MAX_CHANGES
            || unresolvedVariables.size() >= MAX_VARIABLES;

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("path_a", paths.firstRelative());
    result.put("path_b", paths.secondRelative());
    result.put("kind", paths.kind());
    result.put(
        "profiles",
        Map.of(
            "a", profileOutput(first),
            "b", profileOutput(second)));
    result.put("variables_changed", variablesChanged);
    result.put("metadata_references_changed", metadataChanged);
    result.put("run_configuration_changed", runConfigurationChanged);
    result.put("parameter_defaults_changed", parameterDefaultsChanged);
    result.put("unresolved_variables", unresolvedVariables);
    result.put(
        "identical",
        variablesChanged.isEmpty()
            && metadataChanged.isEmpty()
            && runConfigurationChanged.isEmpty()
            && parameterDefaultsChanged.isEmpty()
            && unresolvedVariables.isEmpty());
    result.put("redaction_applied", true);
    result.put("truncated", truncated);
    result.put("comparison_complete", !truncated);
    return result;
  }

  private Profile load(
      java.nio.file.Path path,
      String relativePath,
      String runConfiguration,
      Map<String, String> parameters)
      throws Exception {
    String xml = decode(files.readBytes(path));
    Document document = HopXml.parse(xml);
    DefinitionEnvironment definition = definitionEnvironment(document, xml);
    Map<String, Object> resolved =
        runConfigurations.resolveConfiguration(relativePath, runConfiguration, parameters);
    boolean truncated = definition.truncated();
    Object unresolved = resolved.get("unresolved_references");
    if (unresolved instanceof List<?> list
        && list.size() >= HopRunConfigurationService.MAX_UNRESOLVED_REFERENCES) {
      truncated = true;
    }
    return new Profile(relativePath, runConfiguration, parameters, definition, resolved, truncated);
  }

  private DefinitionEnvironment definitionEnvironment(Document document, String xml) {
    Map<String, SafeValue> variables = new TreeMap<>();
    Map<String, SafeValue> metadata = new TreeMap<>();
    Map<String, Map<String, SafeValue>> parameters = new TreeMap<>();
    boolean[] truncated = {false};

    NodeList variableNodes = document.getDocumentElement().getElementsByTagName("variable");
    for (int i = 0; i < variableNodes.getLength(); i++) {
      Element variable = (Element) variableNodes.item(i);
      String name = firstNonBlank(childText(variable, "name"), variable.getAttribute("name"));
      if (name == null || name.isBlank()) continue;
      String value =
          firstNonBlank(
              childText(variable, "value"),
              childText(variable, "default_value"),
              variable.getAttribute("value"));
      if (value == null) value = "";
      if (variables.size() >= MAX_VARIABLES && !variables.containsKey(name)) {
        truncated[0] = true;
        break;
      }
      variables.put(name, safeValue(name, value));
    }

    NodeList parameterNodes = document.getDocumentElement().getElementsByTagName("parameter");
    for (int i = 0; i < parameterNodes.getLength(); i++) {
      if (i >= MAX_PARAMETERS) {
        truncated[0] = true;
        break;
      }
      Element parameter = (Element) parameterNodes.item(i);
      String name = firstNonBlank(childText(parameter, "name"), parameter.getAttribute("name"));
      if (name == null || name.isBlank()) continue;
      Map<String, SafeValue> properties = new TreeMap<>();
      NodeList children = parameter.getChildNodes();
      for (int j = 0; j < children.getLength(); j++) {
        Node child = children.item(j);
        if (child.getNodeType() != Node.ELEMENT_NODE) continue;
        Element property = (Element) child;
        String key = property.getTagName();
        if (key.equalsIgnoreCase("name")) continue;
        if (properties.size() >= HopDefinitionDiffService.MAX_PROPERTIES) {
          truncated[0] = true;
          break;
        }
        properties.put(key, safeValue(key, property.getTextContent()));
      }
      parameters.put(name, properties);
    }

    collectMetadata(document.getDocumentElement(), "", metadata, truncated);
    if (metadata.size() >= MAX_METADATA_REFERENCES) truncated[0] = true;

    Set<String> references = new TreeSet<>();
    Matcher matcher = VARIABLE_REFERENCE.matcher(SensitiveData.redactSensitiveText(xml));
    while (matcher.find() && references.size() < MAX_VARIABLES) references.add(matcher.group(1));
    if (matcher.find()) truncated[0] = true;
    return new DefinitionEnvironment(variables, metadata, parameters, references, truncated[0]);
  }

  private static void collectMetadata(
      Element element, String parentPath, Map<String, SafeValue> result, boolean[] truncated) {
    NodeList children = element.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child.getNodeType() != Node.ELEMENT_NODE) continue;
      Element current = (Element) child;
      String path =
          parentPath.isEmpty() ? current.getTagName() : parentPath + "." + current.getTagName();
      if (hasElementChildren(current)) {
        collectMetadata(current, path, result, truncated);
        continue;
      }
      if (!isMetadataPath(path)) continue;
      if (result.size() >= MAX_METADATA_REFERENCES && !result.containsKey(path)) {
        truncated[0] = true;
        return;
      }
      result.put(path, safeValue(current.getTagName(), current.getTextContent()));
    }
  }

  private static boolean isMetadataPath(String path) {
    String normalized = path.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    return normalized.contains("connection")
        || normalized.contains("metadata")
        || normalized.contains("runconfiguration")
        || normalized.contains("executioninfolocation")
        || normalized.contains("executiondataprofile");
  }

  private Map<String, SafeValue> mergeVariables(Profile profile) {
    Map<String, SafeValue> result = new TreeMap<>(profile.definition().variables());
    Object configured = profile.resolved().get("variables");
    if (configured instanceof List<?> list) {
      for (Object item : list) {
        if (!(item instanceof Map<?, ?> row)) continue;
        String name = stringValue(row.get("name"));
        if (name.isBlank()) continue;
        String value = stringValue(row.get("value"));
        boolean redacted = Boolean.TRUE.equals(row.get("redacted"));
        result.put(name, new SafeValue(clip(value), redacted));
      }
    }
    return result;
  }

  private static Map<String, SafeValue> configurationValues(Profile profile) {
    Map<String, SafeValue> result = new TreeMap<>();
    result.put("run_configuration", safeValue("run_configuration", profile.runConfiguration()));
    putConfigurationValue(result, "execution_info_location", profile.resolved());
    putConfigurationValue(result, "data_profile", profile.resolved());
    putConfigurationValue(result, "default_selection", profile.resolved());
    Object engine = profile.resolved().get("engine");
    if (engine instanceof Map<?, ?> map) {
      result.put("engine.plugin_id", safeValue("plugin_id", stringValue(map.get("plugin_id"))));
      result.put(
          "engine.plugin_name", safeValue("plugin_name", stringValue(map.get("plugin_name"))));
    }
    return result;
  }

  private static void putConfigurationValue(
      Map<String, SafeValue> result, String name, Map<String, Object> configuration) {
    result.put(name, safeValue(name, stringValue(configuration.get(name))));
  }

  private List<Map<String, Object>> compareValues(
      String keyName, Map<String, SafeValue> before, Map<String, SafeValue> after) {
    List<Map<String, Object>> changed = new ArrayList<>();
    Set<String> keys = new TreeSet<>(before.keySet());
    keys.addAll(after.keySet());
    for (String key : keys) {
      SafeValue oldValue = before.getOrDefault(key, new SafeValue("", false));
      SafeValue newValue = after.getOrDefault(key, new SafeValue("", false));
      if (Objects.equals(oldValue, newValue)) continue;
      if (changed.size() >= MAX_CHANGES) break;
      Map<String, Object> row = new LinkedHashMap<>();
      row.put(keyName, clip(key));
      row.put("before", oldValue.value());
      row.put("after", newValue.value());
      row.put("redacted", oldValue.redacted() || newValue.redacted());
      changed.add(row);
    }
    return changed;
  }

  private static List<Map<String, Object>> compareParameterDefaults(
      Map<String, Map<String, SafeValue>> before, Map<String, Map<String, SafeValue>> after) {
    List<Map<String, Object>> changed = new ArrayList<>();
    Set<String> names = new TreeSet<>(before.keySet());
    names.addAll(after.keySet());
    for (String name : names) {
      Map<String, SafeValue> oldProperties = before.getOrDefault(name, Map.of());
      Map<String, SafeValue> newProperties = after.getOrDefault(name, Map.of());
      Set<String> properties = new TreeSet<>(oldProperties.keySet());
      properties.addAll(newProperties.keySet());
      for (String property : properties) {
        SafeValue oldValue = oldProperties.getOrDefault(property, new SafeValue("", false));
        SafeValue newValue = newProperties.getOrDefault(property, new SafeValue("", false));
        if (Objects.equals(oldValue, newValue)) continue;
        if (changed.size() >= MAX_CHANGES) return changed;
        changed.add(
            Map.of(
                "name", clip(name),
                "property", clip(property),
                "before", oldValue.value(),
                "after", newValue.value(),
                "redacted", oldValue.redacted() || newValue.redacted()));
      }
    }
    return changed;
  }

  private List<Map<String, Object>> unresolved(Profile first, Profile second) {
    List<Map<String, Object>> result = new ArrayList<>();
    addUnresolved(result, "a", first);
    addUnresolved(result, "b", second);
    return result;
  }

  private void addUnresolved(
      List<Map<String, Object>> result, String environment, Profile profile) {
    Set<String> names = new LinkedHashSet<>();
    Object nativeUnresolved = profile.resolved().get("unresolved_references");
    if (nativeUnresolved instanceof List<?> list) {
      for (Object value : list) {
        if (names.size() >= MAX_VARIABLES) break;
        if (value != null && !stringValue(value).isBlank()) names.add(stringValue(value));
      }
    }
    for (String reference : profile.definition().references()) {
      if (names.size() >= MAX_VARIABLES) break;
      if (!knownVariable(reference, profile.parameters(), profile.definition().variables())) {
        names.add(reference);
      }
    }
    for (String name : names) {
      if (result.size() >= MAX_VARIABLES) break;
      result.add(
          Map.of(
              "environment",
              environment,
              "name",
              clip(name),
              "source",
              "definition_or_run_configuration"));
    }
  }

  private boolean knownVariable(
      String name, Map<String, String> parameters, Map<String, SafeValue> definitionVariables) {
    if (parameters.containsKey(name) || definitionVariables.containsKey(name)) return true;
    if (variables == null) return false;
    String token = "${" + name + "}";
    Variables effective = new Variables();
    effective.initializeFrom(variables);
    String resolved = effective.resolve(token);
    return resolved != null && !resolved.equals(token);
  }

  private static Map<String, Object> profileOutput(Profile profile) {
    return Map.of(
        "path", profile.path(),
        "run_configuration", safeValue("run_configuration", profile.runConfiguration()).value(),
        "parameter_names",
            profile.parameters().keySet().stream()
                .sorted()
                .map(HopEnvironmentDiffService::clip)
                .toList());
  }

  private PathPair validatePaths(String pathA, String pathB) throws Exception {
    requirePath(pathA, "path_a");
    requirePath(pathB, "path_b");
    java.nio.file.Path first = files.resolve(pathA);
    java.nio.file.Path second = files.resolve(pathB);
    String firstKind = kind(first);
    String secondKind = kind(second);
    if (firstKind == null || secondKind == null || !firstKind.equals(secondKind)) {
      throw new IllegalArgumentException(
          "path_a and path_b must be matching .hpl or .hwf definitions");
    }
    return new PathPair(first, second, firstKind, files.relative(first), files.relative(second));
  }

  private static Map<String, String> validateParameters(Map<String, String> values, String field) {
    if (values == null || values.isEmpty()) return Map.of();
    if (values.size() > MAX_PARAMETERS) {
      throw new IllegalArgumentException(field + " cannot exceed " + MAX_PARAMETERS + " entries");
    }
    Map<String, String> result = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : values.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      if (key == null || key.isBlank() || key.length() > MAX_NAME_LENGTH) {
        throw new IllegalArgumentException(field + " contains an invalid parameter name");
      }
      if (value == null || value.length() > HopRunConfigurationService.MAX_VALUE_LENGTH) {
        throw new IllegalArgumentException(field + " contains an invalid parameter value");
      }
      result.put(key, value);
    }
    return result;
  }

  private static String defaultConfiguration(String value) {
    if (value == null || value.isBlank()) return "local";
    if (value.length() > MAX_NAME_LENGTH) {
      throw new IllegalArgumentException(
          "run configuration name exceeds " + MAX_NAME_LENGTH + " characters");
    }
    return value;
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

  private static SafeValue safeValue(String key, String value) {
    String raw = value == null ? "" : value;
    boolean redacted = SensitiveData.isSensitiveKey(key) || containsSensitiveReference(raw);
    String safe = redacted ? SensitiveData.REDACTED : SensitiveData.redactSensitiveText(raw);
    if (safe == null) safe = "";
    return new SafeValue(clip(safe), redacted);
  }

  private static boolean containsSensitiveReference(String value) {
    Matcher matcher = VARIABLE_REFERENCE.matcher(value == null ? "" : value);
    while (matcher.find()) {
      if (SensitiveData.isSensitiveKey(matcher.group(1))) return true;
    }
    return false;
  }

  private static String stringValue(Object value) {
    return value == null ? "" : String.valueOf(value);
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

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) return value;
    }
    return null;
  }

  private static boolean hasElementChildren(Element element) {
    NodeList children = element.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      if (children.item(i).getNodeType() == Node.ELEMENT_NODE) return true;
    }
    return false;
  }

  private static String clip(String value) {
    if (value == null) return "";
    return value.length() <= MAX_VALUE_LENGTH ? value : value.substring(0, MAX_VALUE_LENGTH);
  }

  private record PathPair(
      java.nio.file.Path first,
      java.nio.file.Path second,
      String kind,
      String firstRelative,
      String secondRelative) {}

  private record SafeValue(String value, boolean redacted) {}

  private record DefinitionEnvironment(
      Map<String, SafeValue> variables,
      Map<String, SafeValue> metadata,
      Map<String, Map<String, SafeValue>> parameters,
      Set<String> references,
      boolean truncated) {}

  private record Profile(
      String path,
      String runConfiguration,
      Map<String, String> parameters,
      DefinitionEnvironment definition,
      Map<String, Object> resolved,
      boolean truncated) {}
}
