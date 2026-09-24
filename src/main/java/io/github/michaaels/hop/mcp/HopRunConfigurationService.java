package io.github.michaaels.hop.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.hop.core.variables.DescribedVariable;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.metadata.api.IHopMetadataSerializer;
import org.apache.hop.pipeline.config.IPipelineEngineRunConfiguration;
import org.apache.hop.pipeline.config.PipelineRunConfiguration;
import org.apache.hop.workflow.config.IWorkflowEngineRunConfiguration;
import org.apache.hop.workflow.config.WorkflowRunConfiguration;

/** Bounded, secret-safe resolution of native Hop pipeline and workflow run configurations. */
final class HopRunConfigurationService {
  static final int MAX_NAME_LENGTH = 512;
  static final int MAX_PATH_LENGTH = 4096;
  static final int MAX_PARAMETERS = 100;
  static final int MAX_VARIABLES = 200;
  static final int MAX_UNRESOLVED_REFERENCES = 100;
  static final int MAX_VALUE_LENGTH = 4096;

  private static final Pattern VARIABLE_REFERENCE = Pattern.compile("\\$\\{([^}]{1,256})}");

  private final IHopMetadataProvider metadataProvider;
  private final IVariables variables;

  HopRunConfigurationService(IHopMetadataProvider metadataProvider, IVariables variables) {
    this.metadataProvider = metadataProvider;
    this.variables = variables;
  }

  Map<String, Object> resolve(String kind, String name) throws Exception {
    requireKind(kind);
    requireName(name);
    return "pipeline".equals(kind)
        ? resolvePipeline(name, Map.of(), "")
        : resolveWorkflow(name, Map.of(), "");
  }

  Map<String, Object> resolveConfiguration(
      String path, String runConfiguration, Map<String, String> parameters) throws Exception {
    String normalizedPath = requirePath(path);
    requireName(runConfiguration);
    Map<String, String> safeParameters = validateParameters(parameters);
    String kind = normalizedPath.toLowerCase().endsWith(".hpl") ? "pipeline" : "workflow";
    return "pipeline".equals(kind)
        ? resolvePipeline(runConfiguration, safeParameters, normalizedPath)
        : resolveWorkflow(runConfiguration, safeParameters, normalizedPath);
  }

  private Map<String, Object> resolvePipeline(
      String name, Map<String, String> parameters, String path) throws Exception {
    IHopMetadataSerializer<PipelineRunConfiguration> serializer =
        metadataProvider.getSerializer(PipelineRunConfiguration.class);
    PipelineRunConfiguration configuration = serializer.load(name);
    if (configuration == null) throw notFound();

    Variables effective = inheritedVariables(parameters);
    List<Map<String, Object>> resolvedVariables = new ArrayList<>();
    List<String> unresolved = new ArrayList<>();
    List<DescribedVariable> configured = configuration.getConfigurationVariables();
    if (configured != null) {
      int count = 0;
      for (DescribedVariable variable : configured) {
        if (count++ >= MAX_VARIABLES) break;
        addVariable(variable, effective, resolvedVariables, unresolved);
      }
    }
    IPipelineEngineRunConfiguration engine = configuration.getEngineRunConfiguration();
    Map<String, Object> engineResult =
        engine(
            engine == null ? null : engine.getEnginePluginId(),
            engine == null ? null : engine.getEnginePluginName());
    return result(
        "pipeline",
        configuration.getName(),
        path,
        name,
        configuration.getDescription(),
        configuration.getExecutionInfoLocationName(),
        configuration.getExecutionDataProfileName(),
        configuration.isDefaultSelection(),
        engineResult,
        parameters,
        resolvedVariables,
        unresolved);
  }

  private Map<String, Object> resolveWorkflow(
      String name, Map<String, String> parameters, String path) throws Exception {
    IHopMetadataSerializer<WorkflowRunConfiguration> serializer =
        metadataProvider.getSerializer(WorkflowRunConfiguration.class);
    WorkflowRunConfiguration configuration = serializer.load(name);
    if (configuration == null) throw notFound();

    IWorkflowEngineRunConfiguration engine = configuration.getEngineRunConfiguration();
    return result(
        "workflow",
        configuration.getName(),
        path,
        name,
        configuration.getDescription(),
        configuration.getExecutionInfoLocationName(),
        "",
        configuration.isDefaultSelection(),
        engine(
            engine == null ? null : engine.getEnginePluginId(),
            engine == null ? null : engine.getEnginePluginName()),
        parameters,
        List.of(),
        List.of());
  }

  private void addVariable(
      DescribedVariable variable,
      Variables effective,
      List<Map<String, Object>> resolvedVariables,
      List<String> unresolved) {
    if (variable == null) return;
    String name = safe(variable.getName());
    String rawValue = variable.getValue() == null ? "" : variable.getValue();
    String resolvedValue = effective.resolve(rawValue);
    if (resolvedValue == null) resolvedValue = "";
    effective.setVariable(name, resolvedValue);

    List<String> references = references(resolvedValue);
    for (String reference : references) {
      if (!unresolved.contains(reference) && unresolved.size() < MAX_UNRESOLVED_REFERENCES) {
        unresolved.add(reference);
      }
    }
    boolean secret =
        SensitiveData.isSensitiveKey(name)
            || references.stream().anyMatch(SensitiveData::isSensitiveKey)
            || containsSensitiveReference(rawValue);
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("name", name);
    row.put("value", secret ? SensitiveData.REDACTED : boundedValue(resolvedValue));
    row.put("resolved", references.isEmpty());
    row.put("description", boundedValue(variable.getDescription()));
    row.put("redacted", secret);
    resolvedVariables.add(row);
  }

  private Map<String, Object> result(
      String kind,
      String name,
      String path,
      String runConfiguration,
      String description,
      String executionInfoLocation,
      String dataProfile,
      boolean defaultSelection,
      Map<String, Object> engine,
      Map<String, String> parameters,
      List<Map<String, Object>> resolvedVariables,
      List<String> unresolved) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("kind", kind);
    result.put("name", safe(name));
    result.put("path", safe(path));
    result.put("run_configuration", safe(runConfiguration));
    result.put("description", boundedValue(description));
    result.put("execution_info_location", boundedValue(executionInfoLocation));
    result.put("data_profile", boundedValue(dataProfile));
    result.put("default_selection", defaultSelection);
    result.put("engine", engine);
    result.put("parameters", parameterProjection(parameters));
    result.put(
        "effective_configuration",
        Map.of(
            "run_configuration", safe(runConfiguration),
            "engine", engine,
            "execution_info_location", boundedValue(executionInfoLocation),
            "data_profile", boundedValue(dataProfile)));
    result.put("variables", resolvedVariables);
    result.put("unresolved_references", unresolved);
    result.put("variables_truncated", resolvedVariables.size() >= MAX_VARIABLES);
    result.put("redaction_applied", true);
    return result;
  }

  private Variables inheritedVariables(Map<String, String> parameters) {
    Variables effective = new Variables();
    if (variables != null) effective.initializeFrom(variables);
    for (Map.Entry<String, String> parameter : parameters.entrySet()) {
      effective.setVariable(parameter.getKey(), parameter.getValue());
    }
    return effective;
  }

  private static Map<String, Object> parameterProjection(Map<String, String> parameters) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<String, String> parameter : parameters.entrySet()) {
      String key = safe(parameter.getKey());
      String value = parameter.getValue();
      result.put(
          key,
          SensitiveData.isSensitiveKey(key) || containsSensitiveReference(value)
              ? SensitiveData.REDACTED
              : boundedValue(value));
    }
    return result;
  }

  private static Map<String, Object> engine(String id, String name) {
    return Map.of("plugin_id", safe(id), "plugin_name", safe(name));
  }

  private static List<String> references(String value) {
    List<String> references = new ArrayList<>();
    if (value == null) return references;
    Matcher matcher = VARIABLE_REFERENCE.matcher(value);
    while (matcher.find() && references.size() < MAX_UNRESOLVED_REFERENCES) {
      references.add(matcher.group(1));
    }
    return references;
  }

  private static boolean containsSensitiveReference(String value) {
    for (String reference : references(value)) {
      if (SensitiveData.isSensitiveKey(reference)) return true;
    }
    return false;
  }

  private static String boundedValue(String value) {
    String safe = SensitiveData.redactText(value == null ? "" : value);
    if (safe == null) return "";
    return safe.length() <= MAX_VALUE_LENGTH ? safe : safe.substring(0, MAX_VALUE_LENGTH);
  }

  private static String safe(String value) {
    return value == null ? "" : SensitiveData.redactSensitiveText(value);
  }

  private static McpException notFound() {
    return McpException.validation(
        "RUN_CONFIGURATION_NOT_FOUND", "The requested native run configuration was not found.");
  }

  private static void requireKind(String kind) {
    if (!"pipeline".equals(kind) && !"workflow".equals(kind)) {
      throw new IllegalArgumentException("kind must be pipeline or workflow");
    }
  }

  private static void requireName(String name) {
    if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
    if (name.length() > MAX_NAME_LENGTH)
      throw new IllegalArgumentException("name exceeds " + MAX_NAME_LENGTH + " characters");
  }

  private static String requirePath(String path) {
    if (path == null || path.isBlank()) throw new IllegalArgumentException("path is required");
    if (path.length() > MAX_PATH_LENGTH)
      throw new IllegalArgumentException("path exceeds " + MAX_PATH_LENGTH + " characters");
    String normalized = path.replace('\\', '/');
    java.nio.file.Path candidate = java.nio.file.Path.of(normalized).normalize();
    if (candidate.isAbsolute()
        || candidate.startsWith(java.nio.file.Path.of(".."))
        || normalized.contains("//")) {
      throw new IllegalArgumentException("path must be a project-relative .hpl or .hwf path");
    }
    String lower = normalized.toLowerCase(java.util.Locale.ROOT);
    if (!lower.endsWith(".hpl") && !lower.endsWith(".hwf")) {
      throw new IllegalArgumentException("path must end in .hpl or .hwf");
    }
    return normalized;
  }

  private static Map<String, String> validateParameters(Map<String, String> parameters) {
    if (parameters == null || parameters.isEmpty()) return Map.of();
    if (parameters.size() > MAX_PARAMETERS)
      throw new IllegalArgumentException("parameters cannot exceed " + MAX_PARAMETERS + " entries");
    Map<String, String> result = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : parameters.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      if (key == null || key.isBlank() || key.length() > MAX_NAME_LENGTH)
        throw new IllegalArgumentException("parameter names must be non-empty and bounded");
      if (value == null || value.length() > MAX_VALUE_LENGTH)
        throw new IllegalArgumentException("parameter values must be non-null and bounded");
      result.put(key, value);
    }
    return result;
  }
}
