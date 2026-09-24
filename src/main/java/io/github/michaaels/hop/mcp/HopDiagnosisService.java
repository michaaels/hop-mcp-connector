package io.github.michaaels.hop.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Aggregates bounded execution evidence without turning heuristics into diagnosis facts. */
final class HopDiagnosisService {
  static final int MAX_LOCATION_LENGTH = HopExecutionRepository.MAX_LOCATION_LENGTH;
  static final int MAX_PATH_LENGTH = 4096;
  static final int MAX_EXECUTION_ID_LENGTH = 256;
  static final int MAX_CHANNEL_ID_LENGTH = 256;
  static final int MAX_NAME_LENGTH = 512;
  static final int MAX_PREVIOUS_EXECUTIONS = 20;
  static final int MAX_FACTS = 100;
  static final int MAX_POSSIBLE_CAUSES = 50;
  static final int MAX_RECOMMENDATIONS = 50;
  static final int MAX_LOG_ERRORS = 20;
  static final int MAX_CONNECTIONS = 100;
  static final int MAX_METADATA_REFERENCES = 200;
  static final int MAX_PARAMETERS = 100;
  static final int MAX_METRICS = 100;
  static final int MAX_METRIC_VALUES = 16;
  static final int MAX_VALUE_LENGTH = 1024;

  private static final Pattern VARIABLE_REFERENCE = Pattern.compile("\\$\\{([^}]{1,256})}");

  @FunctionalInterface
  interface LogAccess {
    Map<String, Object> read(String channelId, boolean includeGeneral, int from, int to);
  }

  private final ProjectFiles files;
  private final HopExecutionRepository executions;
  private final HopMetadataService metadata;
  private final HopRunConfigurationService runConfigurations;
  private final LogAccess logs;

  HopDiagnosisService(
      ProjectFiles files,
      HopExecutionRepository executions,
      HopMetadataService metadata,
      HopRunConfigurationService runConfigurations,
      LogAccess logs) {
    this.files = Objects.requireNonNull(files, "files");
    this.executions = Objects.requireNonNull(executions, "executions");
    this.metadata = Objects.requireNonNull(metadata, "metadata");
    this.runConfigurations = Objects.requireNonNull(runConfigurations, "runConfigurations");
    this.logs = Objects.requireNonNull(logs, "logs");
  }

  Map<String, Object> diagnose(
      String location,
      String executionId,
      String channelId,
      boolean includeGeneral,
      int logFrom,
      int logTo,
      int previousLimit)
      throws Exception {
    validate(location, executionId, channelId, previousLimit);
    Map<String, Object> executionSnapshot =
        executions.diagnosticSnapshot(location, executionId, previousLimit);
    Map<String, Object> detail = objectMap(executionSnapshot.get("detail"));
    Map<String, Object> summary = objectMap(detail.get("execution"));
    String path = safeText(summary.get("path"), MAX_PATH_LENGTH);
    String runConfiguration = safeText(summary.get("run_configuration"), 512);

    Evidence executionEvidence = executionEvidence(detail, summary);
    Evidence metricEvidence = metricsEvidence(objectMap(executionSnapshot.get("metrics")));
    Evidence logEvidence = logsEvidence(channelId, includeGeneral, logFrom, logTo);
    Evidence definitionEvidence = definitionEvidence(path);
    Evidence configurationEvidence = configurationEvidence(path, runConfiguration);
    Evidence previousEvidence =
        previousEvidence(objectMap(executionSnapshot.get("history")), executionId);

    List<Map<String, Object>> facts = new ArrayList<>();
    addExecutionFacts(facts, summary, detail);
    addEvidenceFacts(
        facts,
        metricEvidence,
        logEvidence,
        definitionEvidence,
        configurationEvidence,
        previousEvidence);
    List<Map<String, Object>> possibleCauses =
        possibleCauses(
            summary,
            metricEvidence,
            logEvidence,
            definitionEvidence,
            configurationEvidence,
            previousEvidence);
    List<Map<String, Object>> recommendations =
        recommendations(
            path, logEvidence, definitionEvidence, configurationEvidence, previousEvidence);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("location", location);
    result.put("execution_id", executionId);
    result.put("path", path);
    result.put("run_configuration", runConfiguration);
    result.put(
        "evidence",
        Map.of(
            "execution", executionEvidence.value(),
            "logs", logEvidence.value(),
            "component_metrics", metricEvidence.value(),
            "metadata", definitionEvidence.value(),
            "connections", connectionsEvidence(definitionEvidence.value()),
            "parameters", parametersEvidence(definitionEvidence.value()),
            "run_configuration", configurationEvidence.value(),
            "previous_executions", previousEvidence.value()));
    result.put("facts", facts);
    result.put("possible_causes", possibleCauses);
    result.put("recommendations", recommendations);
    result.put("redaction_applied", true);
    result.put(
        "evidence_complete",
        executionEvidence.complete()
            && metricEvidence.complete()
            && logEvidence.complete()
            && definitionEvidence.complete()
            && configurationEvidence.complete()
            && previousEvidence.complete());
    result.put(
        "truncated",
        anyTruncated(
            executionEvidence,
            metricEvidence,
            logEvidence,
            definitionEvidence,
            configurationEvidence,
            previousEvidence));
    return result;
  }

  private Evidence executionEvidence(Map<String, Object> detail, Map<String, Object> summary) {
    Map<String, Object> state = objectMap(detail.get("state"));
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("available", true);
    value.put("status", safeText(summary.get("status"), 64));
    value.put("failed", Boolean.TRUE.equals(summary.get("failed")));
    value.put("active", Boolean.TRUE.equals(summary.get("active")));
    value.put("duration_ms", numberValue(summary.get("duration_ms")));
    value.put("state_status", safeText(state.get("status"), 64));
    value.put("state_description", safeText(state.get("status_description"), MAX_VALUE_LENGTH));
    Object errors = detail.get("errors");
    value.put("error_count", errors instanceof List<?> list ? Math.min(list.size(), 50) : 0);
    value.put("errors", boundedStrings(errors, 20, MAX_VALUE_LENGTH));
    return new Evidence(value, true, false);
  }

  private Evidence metricsEvidence(Map<String, Object> raw) {
    try {
      List<Map<String, Object>> components = new ArrayList<>();
      Object rows = raw.get("components");
      if (rows instanceof List<?> list) {
        for (Object item : list) {
          if (components.size() >= MAX_METRICS || !(item instanceof Map<?, ?> row)) break;
          Map<String, Object> output = new LinkedHashMap<>();
          output.put("component", safeText(row.get("component"), 512));
          output.put("copy", safeText(row.get("copy"), 128));
          output.put("metrics", boundedMetricMap(row.get("metrics")));
          components.add(output);
        }
      }
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("available", Boolean.TRUE.equals(raw.get("available")));
      value.put("component_count", components.size());
      value.put(
          "truncated",
          Boolean.TRUE.equals(raw.get("truncated")) || components.size() >= MAX_METRICS);
      value.put("components", components);
      return new Evidence(value, true, Boolean.TRUE.equals(value.get("truncated")));
    } catch (Exception error) {
      return unavailable("METRICS_UNAVAILABLE");
    }
  }

  private Evidence logsEvidence(String channelId, boolean includeGeneral, int from, int to) {
    if (channelId == null || channelId.isBlank()) {
      return unavailable("LOG_CHANNEL_NOT_PROVIDED");
    }
    try {
      Map<String, Object> raw = logs.read(channelId, includeGeneral, from, to);
      List<Map<String, Object>> errors = new ArrayList<>();
      int errorCount = 0;
      Object events = raw.get("events");
      if (events instanceof List<?> list) {
        for (Object item : list) {
          if (!(item instanceof Map<?, ?> event)) continue;
          String level = safeText(event.get("level"), 32);
          if (!"ERROR".equalsIgnoreCase(level) && !"FATAL".equalsIgnoreCase(level)) continue;
          errorCount++;
          if (errors.size() < MAX_LOG_ERRORS) {
            errors.add(
                Map.of(
                    "timestamp", numberValue(event.get("timestamp")),
                    "level", level,
                    "message", safeText(event.get("message"), MAX_VALUE_LENGTH)));
          }
        }
      }
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("available", true);
      value.put("event_count", numberValue(raw.get("count")));
      value.put("error_count", errorCount);
      value.put("errors", errors);
      value.put(
          "truncated", Boolean.TRUE.equals(raw.get("truncated")) || errorCount > errors.size());
      return new Evidence(value, true, Boolean.TRUE.equals(value.get("truncated")));
    } catch (Exception error) {
      return unavailable("LOGS_UNAVAILABLE");
    }
  }

  private Evidence definitionEvidence(String path) {
    if (path == null || path.isBlank()) return unavailable("DEFINITION_PATH_UNAVAILABLE");
    try {
      String xml = files.readText(path);
      Document document = HopXml.parse(xml);
      Map<String, Object> inspection = HopXml.inspect(path, document);
      Map<String, SafeText> references = new LinkedHashMap<>();
      collectMetadata(document.getDocumentElement(), "", references);
      List<Map<String, Object>> metadataRows = new ArrayList<>();
      for (Map.Entry<String, SafeText> entry : references.entrySet()) {
        if (metadataRows.size() >= MAX_METADATA_REFERENCES) break;
        metadataRows.add(
            Map.of(
                "path",
                clip(entry.getKey()),
                "value",
                entry.getValue().value(),
                "redacted",
                entry.getValue().redacted()));
      }
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("available", true);
      value.put("type", safeText(inspection.get("type"), 32));
      value.put("name", safeText(inspection.get("name"), 512));
      value.put("metadata_references", metadataRows);
      value.put("definition_references", boundedStrings(inspection.get("references"), 200, 512));
      value.put("tables", boundedStrings(inspection.get("tables"), 200, 512));
      value.put("parameters", parameters(document));
      value.put("truncated", metadataRows.size() >= MAX_METADATA_REFERENCES);
      return new Evidence(value, true, Boolean.TRUE.equals(value.get("truncated")));
    } catch (Exception error) {
      return unavailable("DEFINITION_EVIDENCE_UNAVAILABLE");
    }
  }

  private Evidence configurationEvidence(String path, String runConfiguration) {
    if (path == null || path.isBlank() || runConfiguration.isBlank()) {
      return unavailable("RUN_CONFIGURATION_INPUT_UNAVAILABLE");
    }
    try {
      Map<String, Object> resolved =
          runConfigurations.resolveConfiguration(path, runConfiguration, Map.of());
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("available", true);
      value.put("name", safeText(resolved.get("name"), 512));
      value.put("run_configuration", safeText(resolved.get("run_configuration"), 512));
      value.put("engine", safeMap(resolved.get("engine"), 512));
      value.put(
          "execution_info_location",
          safeText(resolved.get("execution_info_location"), MAX_VALUE_LENGTH));
      value.put("data_profile", safeText(resolved.get("data_profile"), MAX_VALUE_LENGTH));
      value.put(
          "unresolved_references", boundedStrings(resolved.get("unresolved_references"), 100, 512));
      value.put("redaction_applied", true);
      return new Evidence(value, true, Boolean.TRUE.equals(resolved.get("variables_truncated")));
    } catch (Exception error) {
      return unavailable("RUN_CONFIGURATION_UNAVAILABLE");
    }
  }

  private Evidence previousEvidence(Map<String, Object> raw, String currentId) {
    try {
      List<Map<String, Object>> rows = new ArrayList<>();
      Object executionsValue = raw.get("executions");
      if (executionsValue instanceof List<?> list) {
        for (Object item : list) {
          if (rows.size() >= MAX_PREVIOUS_EXECUTIONS || !(item instanceof Map<?, ?> row)) break;
          if (currentId.equals(String.valueOf(row.get("execution_id")))) continue;
          Map<String, Object> output = new LinkedHashMap<>();
          output.put("execution_id", safeText(row.get("execution_id"), 256));
          output.put("status", safeText(row.get("status"), 64));
          output.put("failed", Boolean.TRUE.equals(row.get("failed")));
          output.put("run_configuration", safeText(row.get("run_configuration"), 512));
          output.put("start_epoch_ms", numberValue(row.get("start_epoch_ms")));
          output.put("duration_ms", numberValue(row.get("duration_ms")));
          rows.add(output);
        }
      }
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("available", true);
      value.put("count", numberValue(raw.get("count")));
      value.put("returned", rows.size());
      value.put("has_more", Boolean.TRUE.equals(raw.get("has_more")));
      value.put("executions", rows);
      return new Evidence(
          value,
          Boolean.TRUE.equals(raw.get("count_complete")),
          Boolean.TRUE.equals(raw.get("has_more")));
    } catch (Exception error) {
      return unavailable("PREVIOUS_EXECUTIONS_UNAVAILABLE");
    }
  }

  private Map<String, Object> connectionsEvidence(Map<String, Object> metadataEvidence) {
    List<Map<String, Object>> rows = new ArrayList<>();
    Object refs = metadataEvidence.get("metadata_references");
    Set<String> names = new LinkedHashSet<>();
    if (refs instanceof List<?> list) {
      for (Object item : list) {
        if (!(item instanceof Map<?, ?> row)) continue;
        String path = safeText(row.get("path"), 512).toLowerCase(Locale.ROOT);
        if (!path.contains("connection")) continue;
        String name = safeText(row.get("value"), 512);
        if (!name.isBlank()) names.add(name);
      }
    }
    boolean checked = !names.isEmpty();
    for (String name : names.stream().limit(MAX_CONNECTIONS).toList()) {
      boolean metadataAvailable = false;
      try {
        metadata.get("rdbms", name);
        metadataAvailable = true;
      } catch (Exception ignored) {
        // A connection reference is still useful evidence when its metadata object is absent.
      }
      rows.add(Map.of("name", name, "metadata_available", metadataAvailable));
    }
    return Map.of("available", !rows.isEmpty(), "references", rows, "metadata_checked", checked);
  }

  private static Map<String, Object> parametersEvidence(Map<String, Object> metadataEvidence) {
    Object parameters = metadataEvidence.get("parameters");
    if (parameters instanceof List<?> list) {
      return Map.of("available", true, "defaults", list, "count", list.size());
    }
    return Map.of("available", false, "defaults", List.of(), "count", 0);
  }

  private List<Map<String, Object>> possibleCauses(
      Map<String, Object> summary,
      Evidence metrics,
      Evidence logs,
      Evidence definition,
      Evidence configuration,
      Evidence previous) {
    List<Map<String, Object>> result = new ArrayList<>();
    if (Boolean.TRUE.equals(summary.get("failed"))
        && Boolean.TRUE.equals(logs.value().get("available"))
        && numberValue(logs.value().get("error_count")).longValue() > 0) {
      addCause(
          result,
          "logged_error",
          "low",
          "Redacted ERROR/FATAL events were observed in the execution log.",
          List.of("logs.error_count"));
    }
    if (Boolean.TRUE.equals(configuration.value().get("available"))
        && !list(configuration.value().get("unresolved_references")).isEmpty()) {
      addCause(
          result,
          "unresolved_run_variable",
          "low",
          "The native run configuration reported unresolved variable references.",
          List.of("run_configuration.unresolved_references"));
    }
    if (!Boolean.TRUE.equals(configuration.value().get("available"))) {
      addCause(
          result,
          "run_configuration_unavailable",
          "low",
          "The native run configuration could not be resolved; this is evidence absence, not proof of causality.",
          List.of("run_configuration.available"));
    }
    if (Boolean.TRUE.equals(summary.get("failed")) && !logs.complete() && !definition.complete()) {
      addCause(
          result,
          "insufficient_evidence",
          "low",
          "The available bounded evidence is incomplete, so no stronger cause can be stated.",
          List.of("logs", "metadata"));
    }
    if (result.isEmpty() && Boolean.TRUE.equals(summary.get("failed"))) {
      addCause(
          result,
          "undetermined",
          "low",
          "The execution failed but the bounded evidence does not identify a verified cause.",
          List.of("execution.status"));
    }
    return result;
  }

  private List<Map<String, Object>> recommendations(
      String path, Evidence logs, Evidence definition, Evidence configuration, Evidence previous) {
    List<Map<String, Object>> result = new ArrayList<>();
    if (!Boolean.TRUE.equals(logs.value().get("available"))) {
      addRecommendation(
          result,
          "collect_logs",
          "Provide the execution log channel to inspect redacted execution events.",
          "hop_logs",
          false);
    }
    if (!Boolean.TRUE.equals(configuration.value().get("available"))
        || !list(configuration.value().get("unresolved_references")).isEmpty()) {
      addRecommendation(
          result,
          "resolve_configuration",
          "Review the native run configuration and unresolved references.",
          "hop_resolve_configuration",
          false);
    }
    if (Boolean.TRUE.equals(definition.value().get("available"))
        && !list(definition.value().get("metadata_references")).isEmpty()) {
      addRecommendation(
          result,
          "inspect_metadata",
          "Inspect referenced metadata objects before changing the definition.",
          "hop_metadata_get",
          false);
    }
    if (Boolean.TRUE.equals(previous.value().get("available"))
        && numberValue(previous.value().get("returned")).longValue() > 0) {
      addRecommendation(
          result,
          "compare_previous_execution",
          "Compare the current execution with bounded previous executions and environment configuration.",
          "hop_environment_diff",
          false);
    }
    if (result.isEmpty()) {
      addRecommendation(
          result,
          "review_evidence",
          "Review the returned facts and possible causes; no correction was applied.",
          path.isBlank() ? "hop_execution_detail" : "hop_execution_metrics",
          false);
    }
    return result;
  }

  private static void addExecutionFacts(
      List<Map<String, Object>> facts, Map<String, Object> summary, Map<String, Object> detail) {
    addFact(facts, "execution.status", "execution", "status", safeText(summary.get("status"), 64));
    addFact(
        facts,
        "execution.failed",
        "execution",
        "failed",
        Boolean.TRUE.equals(summary.get("failed")));
    addFact(
        facts,
        "execution.active",
        "execution",
        "active",
        Boolean.TRUE.equals(summary.get("active")));
    addFact(
        facts,
        "execution.duration_ms",
        "execution",
        "duration_ms",
        numberValue(summary.get("duration_ms")));
    addFact(
        facts,
        "execution.error_count",
        "execution",
        "error_count",
        detail.get("errors") instanceof List<?> list ? list.size() : 0);
  }

  private static void addEvidenceFacts(
      List<Map<String, Object>> facts,
      Evidence metrics,
      Evidence logs,
      Evidence definition,
      Evidence configuration,
      Evidence previous) {
    addFact(
        facts,
        "metrics.available",
        "component_metrics",
        "available",
        metrics.value().get("available"));
    addFact(
        facts,
        "metrics.component_count",
        "component_metrics",
        "component_count",
        metrics.value().get("component_count"));
    addFact(facts, "logs.available", "logs", "available", logs.value().get("available"));
    addFact(
        facts,
        "logs.error_count",
        "logs",
        "error_count",
        logs.value().getOrDefault("error_count", 0));
    addFact(
        facts, "metadata.available", "metadata", "available", definition.value().get("available"));
    addFact(
        facts,
        "run_configuration.available",
        "run_configuration",
        "available",
        configuration.value().get("available"));
    addFact(
        facts,
        "previous.count",
        "previous_executions",
        "count",
        previous.value().getOrDefault("count", 0));
  }

  private static void addFact(
      List<Map<String, Object>> facts, String id, String source, String name, Object value) {
    if (facts.size() >= MAX_FACTS) return;
    facts.add(
        Map.of(
            "id",
            id,
            "source",
            source,
            "name",
            name,
            "value",
            value == null ? "" : value,
            "observed",
            true));
  }

  private static void addCause(
      List<Map<String, Object>> causes,
      String code,
      String confidence,
      String explanation,
      List<String> evidence) {
    if (causes.size() >= MAX_POSSIBLE_CAUSES) return;
    causes.add(
        Map.of(
            "code",
            code,
            "confidence",
            confidence,
            "explanation",
            explanation,
            "evidence",
            evidence,
            "verified",
            false));
  }

  private static void addRecommendation(
      List<Map<String, Object>> recommendations,
      String code,
      String reason,
      String tool,
      boolean requiresPreview) {
    if (recommendations.size() >= MAX_RECOMMENDATIONS) return;
    recommendations.add(
        Map.of(
            "code",
            code,
            "reason",
            reason,
            "tool",
            tool,
            "requires_preview",
            requiresPreview,
            "auto_applied",
            false));
  }

  private static Evidence unavailable(String reason) {
    return new Evidence(Map.of("available", false, "reason", reason), false, false);
  }

  private static boolean anyTruncated(Evidence... evidence) {
    for (Evidence item : evidence) if (item.truncated()) return true;
    return false;
  }

  private static void validate(
      String location, String executionId, String channelId, int previousLimit) {
    if (location == null || location.isBlank() || location.length() > MAX_LOCATION_LENGTH)
      throw new IllegalArgumentException("location is required and bounded");
    if (executionId == null
        || executionId.isBlank()
        || executionId.length() > MAX_EXECUTION_ID_LENGTH)
      throw new IllegalArgumentException("execution_id is required and bounded");
    if (channelId != null && channelId.length() > MAX_CHANNEL_ID_LENGTH)
      throw new IllegalArgumentException("channel_id is too long");
    if (previousLimit < 1 || previousLimit > MAX_PREVIOUS_EXECUTIONS)
      throw new IllegalArgumentException(
          "max_previous must be between 1 and " + MAX_PREVIOUS_EXECUTIONS);
  }

  private static Map<String, Object> objectMap(Object value) {
    if (value instanceof Map<?, ?> source) {
      Map<String, Object> result = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : source.entrySet())
        result.put(String.valueOf(entry.getKey()), entry.getValue());
      return result;
    }
    return new LinkedHashMap<>();
  }

  private static List<String> boundedStrings(Object value, int maxItems, int maxLength) {
    List<String> result = new ArrayList<>();
    if (value instanceof Iterable<?> iterable) {
      for (Object item : iterable) {
        if (result.size() >= maxItems) break;
        result.add(safeText(item, maxLength));
      }
    }
    return result;
  }

  private static List<Map<String, Object>> parameters(Document document) {
    List<Map<String, Object>> result = new ArrayList<>();
    NodeList nodes = document.getDocumentElement().getElementsByTagName("parameter");
    for (int i = 0; i < nodes.getLength() && result.size() < MAX_PARAMETERS; i++) {
      Element parameter = (Element) nodes.item(i);
      String name = firstNonBlank(childText(parameter, "name"), parameter.getAttribute("name"));
      if (name == null || name.isBlank()) continue;
      String defaultValue =
          firstNonBlank(
              childText(parameter, "default_value"),
              childText(parameter, "defaultValue"),
              childText(parameter, "value"));
      if (defaultValue == null) defaultValue = "";
      result.add(
          Map.of(
              "name",
              clip(name),
              "default",
              safeValue(name, defaultValue).value(),
              "redacted",
              SensitiveData.isSensitiveKey(name)));
    }
    return result;
  }

  private static void collectMetadata(
      Element element, String parentPath, Map<String, SafeText> result) {
    NodeList children = element.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child.getNodeType() != Node.ELEMENT_NODE) continue;
      Element current = (Element) child;
      String path =
          parentPath.isEmpty() ? current.getTagName() : parentPath + "." + current.getTagName();
      if (hasElementChildren(current)) {
        collectMetadata(current, path, result);
      } else if (isMetadataPath(path) && result.size() < MAX_METADATA_REFERENCES) {
        result.put(path, safeValue(current.getTagName(), current.getTextContent()));
      }
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

  private static boolean hasElementChildren(Element element) {
    NodeList children = element.getChildNodes();
    for (int i = 0; i < children.getLength(); i++)
      if (children.item(i).getNodeType() == Node.ELEMENT_NODE) return true;
    return false;
  }

  private static SafeText safeValue(String key, String value) {
    String raw = value == null ? "" : value;
    boolean redacted = SensitiveData.isSensitiveKey(key) || containsSensitiveReference(raw);
    return new SafeText(
        clip(redacted ? SensitiveData.REDACTED : SensitiveData.redactSensitiveText(raw)), redacted);
  }

  private static boolean containsSensitiveReference(String value) {
    Matcher matcher = VARIABLE_REFERENCE.matcher(value == null ? "" : value);
    while (matcher.find()) if (SensitiveData.isSensitiveKey(matcher.group(1))) return true;
    return false;
  }

  private static Map<String, Object> safeMap(Object value, int maxLength) {
    Map<String, Object> result = new LinkedHashMap<>();
    if (value instanceof Map<?, ?> map) {
      map.keySet().stream()
          .map(String::valueOf)
          .sorted()
          .limit(16)
          .forEach(key -> result.put(key, safeText(map.get(key), maxLength)));
    }
    return result;
  }

  private static Map<String, Object> boundedMetricMap(Object value) {
    Map<String, Object> result = new LinkedHashMap<>();
    if (value instanceof Map<?, ?> map) {
      map.keySet().stream()
          .map(String::valueOf)
          .sorted()
          .limit(MAX_METRIC_VALUES)
          .forEach(key -> result.put(clip(key), numberValue(map.get(key))));
    }
    return result;
  }

  private static List<?> list(Object value) {
    return value instanceof List<?> result ? result : List.of();
  }

  private static Number numberValue(Object value) {
    return value instanceof Number number ? number : 0L;
  }

  private static String safeText(Object value, int maxLength) {
    String text = value == null ? "" : String.valueOf(value);
    String redacted = SensitiveData.redactText(text);
    if (redacted == null) return "";
    return redacted.length() <= maxLength ? redacted : redacted.substring(0, maxLength);
  }

  private static String clip(String value) {
    return safeText(value, MAX_VALUE_LENGTH);
  }

  private static String childText(Element parent, String tag) {
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child.getNodeType() == Node.ELEMENT_NODE && tag.equals(child.getNodeName()))
        return child.getTextContent() == null ? "" : child.getTextContent().trim();
    }
    return "";
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) if (value != null && !value.isBlank()) return value;
    return null;
  }

  private record SafeText(String value, boolean redacted) {}

  private record Evidence(Map<String, Object> value, boolean complete, boolean truncated) {}
}
