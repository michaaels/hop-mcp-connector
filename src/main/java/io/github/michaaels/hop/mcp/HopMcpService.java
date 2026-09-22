package io.github.michaaels.hop.mcp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.hop.core.logging.HopLogStore;
import org.apache.hop.core.logging.HopLoggingEvent;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.metadata.api.IHopMetadataProvider;

final class HopMcpService implements AutoCloseable {
  private final ProjectFiles files;
  private final IVariables variables;
  private final IHopMetadataProvider metadataProvider;
  private final boolean allowDeepCheck;
  private final boolean allowExecution;
  private final boolean allowMutation;
  private final boolean allowWebApi;
  private final HopWebClient webClient;
  private final HopExecutionManager executionManager;
  private final HopComponentAuthoring componentAuthoring;
  private final HopDefinitionMutator definitionMutator;
  private final HopCorrectionPlanManager correctionPlans;
  private final HopSemanticEventSink semanticEventSink;

  HopMcpService(
      ProjectFiles files,
      IVariables variables,
      IHopMetadataProvider metadataProvider,
      boolean allowDeepCheck) {
    this(files, variables, metadataProvider, allowDeepCheck, false, false, false, null);
  }

  HopMcpService(
      ProjectFiles files,
      IVariables variables,
      IHopMetadataProvider metadataProvider,
      boolean allowDeepCheck,
      boolean allowWebApi,
      HopWebClient webClient) {
    this(files, variables, metadataProvider, allowDeepCheck, false, false, allowWebApi, webClient);
  }

  HopMcpService(
      ProjectFiles files,
      IVariables variables,
      IHopMetadataProvider metadataProvider,
      boolean allowDeepCheck,
      boolean allowExecution,
      boolean allowMutation,
      boolean allowWebApi,
      HopWebClient webClient) {
    this(
        files,
        variables,
        metadataProvider,
        allowDeepCheck,
        allowExecution,
        allowMutation,
        allowWebApi,
        webClient,
        HopSemanticEventSink.NONE);
  }

  HopMcpService(
      ProjectFiles files,
      IVariables variables,
      IHopMetadataProvider metadataProvider,
      boolean allowDeepCheck,
      boolean allowExecution,
      boolean allowMutation,
      boolean allowWebApi,
      HopWebClient webClient,
      HopSemanticEventSink semanticEventSink) {
    this.files = files;
    this.variables = variables;
    this.metadataProvider = metadataProvider;
    this.allowDeepCheck = allowDeepCheck;
    this.allowExecution = allowExecution;
    this.allowMutation = allowMutation;
    this.allowWebApi = allowWebApi;
    this.webClient = webClient;
    this.executionManager = new HopExecutionManager();
    this.componentAuthoring = new HopComponentAuthoring(metadataProvider);
    this.semanticEventSink =
        semanticEventSink == null ? HopSemanticEventSink.NONE : semanticEventSink;
    this.definitionMutator =
        new HopDefinitionMutator(files, variables, metadataProvider, this.semanticEventSink);
    this.correctionPlans = new HopCorrectionPlanManager(definitionMutator);
  }

  Map<String, Object> config() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("version", HopMcpVersion.current());
    result.put("project_root", files.root().toString());
    result.put("transport", "stdio");
    result.put("read_only", !allowMutation);
    result.put("allow_deep_check", allowDeepCheck);
    result.put("allow_execution", allowExecution);
    result.put("allow_mutation", allowMutation);
    result.put("allow_web_api", allowWebApi);
    result.put("web_api_configured", webClient != null);
    result.put("web_api_base", webClient == null ? "" : webClient.baseUrl());
    result.put("max_read_bytes", ProjectFiles.MAX_READ_BYTES);
    result.put("max_scan_files", ProjectFiles.MAX_SCAN_FILES);
    return result;
  }

  boolean isToolEnabled(String name) {
    return switch (name) {
      case "hop_deep_check" -> allowDeepCheck;
      case "hop_test_definition",
              "hop_execute",
              "hop_start_execution",
              "hop_execution_status",
              "hop_stop_execution",
              "hop_logs" ->
          allowExecution;
      case "hop_component_types",
              "hop_component_schema",
              "hop_prepare_correction_plan",
              "hop_apply_correction_plan",
              "hop_correction_plan_status",
              "hop_mutate_definition",
              "hop_rollback_mutation" ->
          allowMutation;
      case "hop_web_request" -> allowWebApi;
      default -> true;
    };
  }

  Map<String, Object> plugins() {
    return HopNative.plugins();
  }

  Map<String, Object> plugins(String type, String query, int offset, int limit) {
    return HopNative.plugins(type, query, offset, limit);
  }

  Map<String, Object> componentTypes(String kind, String query, int offset, int limit) {
    requireMutation();
    return componentAuthoring.types(kind, query, offset, limit);
  }

  Map<String, Object> componentSchema(String kind, String pluginId) throws Exception {
    requireMutation();
    return componentAuthoring.schema(kind, pluginId);
  }

  Map<String, Object> catalog(String glob, int offset, int limit) throws Exception {
    return files.catalog(glob, offset, limit);
  }

  Map<String, Object> listDefinitions() throws Exception {
    List<Map<String, Object>> defs = new ArrayList<>();
    for (Path p : files.definitions())
      defs.add(
          Map.of(
              "path",
              files.relative(p),
              "type",
              p.toString().toLowerCase().endsWith(".hpl") ? "pipeline" : "workflow"));
    return Map.of("definitions", defs, "count", defs.size());
  }

  Map<String, Object> inspect(String path) throws Exception {
    return HopXml.inspect(path, readDefinition(path));
  }

  Map<String, Object> context(String path) throws Exception {
    String definition = readDefinition(path);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("path", path);
    out.put("type", path.toLowerCase(Locale.ROOT).endsWith(".hpl") ? "pipeline" : "workflow");
    out.put("inspection", safe("inspect", () -> HopXml.inspect(path, definition)));
    out.put("validation", safe("validate", () -> HopXml.validate(path, definition)));
    out.put("dependencies", dependencies(path));
    return out;
  }

  Map<String, Object> component(String path, String component) throws Exception {
    return HopXml.component(path, readDefinition(path), component);
  }

  Map<String, Object> validate(String path) throws Exception {
    return HopXml.validate(path, readDefinition(path));
  }

  Map<String, Object> lineage(String path, String component, String direction, int maxDepth)
      throws Exception {
    return Map.of(
        "path",
        path,
        "component",
        component,
        "direction",
        direction,
        "edges",
        HopXml.lineage(readDefinition(path), component, direction, maxDepth));
  }

  Map<String, Object> readText(String path) throws Exception {
    return Map.of("path", path, "text", files.readText(path));
  }

  Map<String, Object> search(String query, String glob) throws Exception {
    var r = files.search(query, glob);
    return Map.of("query", query, "results", r, "count", r.size());
  }

  Map<String, Object> findTable(String table) throws Exception {
    if (table == null || table.isBlank()) throw new IllegalArgumentException("table is required");
    List<Map<String, Object>> matches = new ArrayList<>();
    String needle = table.toLowerCase();
    for (Path p : files.definitions()) {
      String rel = files.relative(p), text = files.readText(rel);
      for (String t : HopXml.findTables(text))
        if (t.toLowerCase().contains(needle)) matches.add(Map.of("path", rel, "table", t));
      if (matches.size() >= ProjectFiles.MAX_RESULTS) break;
    }
    return Map.of("table", table, "matches", matches, "count", matches.size());
  }

  Map<String, Object> dependencies(String path) throws Exception {
    String xml = readDefinition(path);
    Set<String> refs = new LinkedHashSet<>(HopXml.references(xml));
    List<Map<String, Object>> resolved = new ArrayList<>();
    for (String ref : refs) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("reference", ref);
      try {
        Path p = resolveReference(path, ref);
        row.put("resolved", files.relative(p));
        row.put("exists", true);
      } catch (Exception e) {
        row.put("exists", false);
        row.put("error", HopXml.redact(String.valueOf(e.getMessage())));
      }
      resolved.add(row);
    }
    return Map.of("path", path, "dependencies", resolved, "count", resolved.size());
  }

  Map<String, Object> deepCheck(String path) throws Exception {
    if (!allowDeepCheck)
      throw new SecurityException(
          "Deep check disabled. Restart with --allow-deep-check; it may access configured external systems.");
    return HopNative.deepCheck(resolveDefinition(path), variables, metadataProvider);
  }

  Map<String, Object> testDefinition(
      String path,
      boolean requestDeepCheck,
      boolean requestExecution,
      String runConfiguration,
      Map<String, String> parameters,
      int timeoutSeconds)
      throws Exception {
    requireExecution();
    if (requestDeepCheck && !allowDeepCheck) {
      throw new SecurityException(
          "Deep check disabled. Restart with --allow-deep-check; it may access configured external systems.");
    }
    Map<String, Object> structural = validate(path);
    Map<String, Object> deep = null;
    Map<String, Object> execution = null;
    Map<String, Object> executionLogs = null;
    String skippedReason = "";
    if (!Boolean.TRUE.equals(structural.get("valid"))) {
      skippedReason = "structural_validation_failed";
    } else if (requestDeepCheck) {
      deep = deepCheck(path);
      if (!Boolean.TRUE.equals(deep.get("valid"))) skippedReason = "deep_check_failed";
    }
    if (requestExecution && skippedReason.isEmpty()) {
      execution = execute(path, runConfiguration, parameters, timeoutSeconds);
      String channel = String.valueOf(execution.getOrDefault("log_channel_id", ""));
      if (!channel.isBlank()) executionLogs = logs(channel, false, -1, 0);
    }
    return HopDefinitionTestReport.build(
        path,
        structural,
        requestDeepCheck,
        deep,
        requestExecution,
        execution,
        executionLogs,
        skippedReason);
  }

  Map<String, Object> execute(
      String path, String runConfiguration, Map<String, String> parameters, int timeoutSeconds)
      throws Exception {
    requireExecution();
    Map<String, Object> result =
        HopNative.execute(
            resolveDefinition(path),
            variables,
            metadataProvider,
            runConfiguration,
            parameters,
            timeoutSeconds);
    result.put("path", path);
    return result;
  }

  Map<String, Object> startExecution(
      String path, String runConfiguration, Map<String, String> parameters, int timeoutSeconds)
      throws Exception {
    requireExecution();
    Map<String, Object> result =
        executionManager.start(
            resolveDefinition(path),
            variables,
            metadataProvider,
            runConfiguration,
            parameters,
            timeoutSeconds);
    result.put("path", path);
    return result;
  }

  Map<String, Object> executionStatus(String operationId) {
    requireExecution();
    Map<String, Object> result = executionManager.status(operationId);
    normalizeExecutionPath(result);
    return result;
  }

  Map<String, Object> stopExecution(String operationId) {
    requireExecution();
    Map<String, Object> result = executionManager.stop(operationId);
    normalizeExecutionPath(result);
    return result;
  }

  Map<String, Object> logs(String channelId, boolean includeGeneral, int from, int to) {
    requireExecution();
    int last = HopLogStore.getLastBufferLineNr();
    int start = from < 0 ? Math.max(0, last - 200) : from;
    int end = to <= 0 ? last : Math.min(to, start + 500);
    if (start < 0 || end < start) throw new IllegalArgumentException("Invalid log cursor range");
    List<HopLoggingEvent> events =
        HopLogStore.getLogBufferFromTo(
            channelId == null || channelId.isBlank() ? null : List.of(channelId),
            includeGeneral,
            start,
            end);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (HopLoggingEvent event : events) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("timestamp", event.getTimeStamp());
      row.put("level", event.getLevel() == null ? "" : event.getLevel().getCode());
      row.put("message", HopXml.redact(String.valueOf(event.getMessage())));
      rows.add(row);
    }
    return Map.of(
        "from", start, "to", end, "last_line", last, "count", rows.size(), "events", rows);
  }

  Map<String, Object> capabilities() {
    return HopSemanticCapabilities.describe(allowMutation, semanticEventSink.isAvailable());
  }

  Map<String, Object> liveUiStatus(String transactionId) throws Exception {
    return semanticEventSink.status(transactionId);
  }

  Map<String, Object> mutateDefinition(
      String path,
      String kind,
      List<Map<String, Object>> operations,
      String expectedSha256,
      boolean apply)
      throws Exception {
    requireMutation();
    return definitionMutator.mutate(path, kind, operations, expectedSha256, apply);
  }

  Map<String, Object> prepareCorrectionPlan(
      String path, String kind, List<Map<String, Object>> operations) throws Exception {
    requireMutation();
    return correctionPlans.prepare(path, kind, operations);
  }

  Map<String, Object> applyCorrectionPlan(String planId, String planSha256) throws Exception {
    requireMutation();
    return correctionPlans.apply(planId, planSha256);
  }

  Map<String, Object> correctionPlanStatus(String planId) {
    requireMutation();
    return correctionPlans.status(planId);
  }

  Map<String, Object> rollbackMutation(String transactionId, String expectedSha256)
      throws Exception {
    requireMutation();
    return definitionMutator.rollback(transactionId, expectedSha256);
  }

  private void requireMutation() {
    if (!allowMutation)
      throw new SecurityException("Native mutation disabled. Restart with --allow-mutation.");
  }

  Map<String, Object> webRequest(String method, String path, Map<String, String> headers)
      throws Exception {
    if (!allowWebApi) {
      throw new SecurityException(
          "Hop Web REST API disabled. Restart with --allow-web-api and configure --web-url or HOP_MCP_WEB_URL.");
    }
    if (webClient == null) {
      throw new IllegalStateException(
          "Hop Web REST API is not configured. Set --web-url or HOP_MCP_WEB_URL.");
    }
    String requestMethod = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
    if (!Set.of("GET", "HEAD").contains(requestMethod)) {
      throw new SecurityException("Only GET and HEAD Hop Web requests are allowed");
    }
    return webClient.request(requestMethod, path, headers);
  }

  private String readDefinition(String path) throws Exception {
    return files.readText(validateDefinition(path));
  }

  private Path resolveDefinition(String path) throws Exception {
    return files.resolve(validateDefinition(path));
  }

  private Path resolveReference(String definitionPath, String reference) throws Exception {
    Path definition = resolveDefinition(definitionPath);
    String resolvedReference = reference.replace("${PROJECT_HOME}", files.root().toString());
    if (variables != null) resolvedReference = variables.resolve(resolvedReference);
    resolvedReference =
        resolvedReference.replace(
            "${Internal.Entry.Current.Folder}", definition.getParent().toString());
    Path candidate = definition.getParent().resolve(resolvedReference).normalize();
    if (!candidate.startsWith(files.root()))
      throw new IllegalArgumentException("Dependency escapes project root: " + reference);
    return files.resolve(files.relative(candidate));
  }

  private Map<String, Object> safe(String operation, CheckedMap call) {
    try {
      return call.call();
    } catch (Exception e) {
      Map<String, Object> result = errorPayload(e);
      result.put("ok", false);
      result.put("operation", operation);
      return result;
    }
  }

  Map<String, Object> errorPayload(Exception exception) {
    String category;
    String code;
    boolean retryable = false;
    String message;
    if (exception instanceof SecurityException) {
      category = "AUTHORIZATION";
      code = "AUTHORIZATION_DENIED";
      message = "Operation is not authorized. Enable the matching server option.";
    } else if (exception instanceof IllegalArgumentException) {
      category = "VALIDATION";
      code = "INVALID_INPUT";
      message = safeExceptionMessage(exception);
    } else if (exception instanceof java.nio.file.NoSuchFileException
        || exception instanceof java.io.FileNotFoundException) {
      category = "NOT_FOUND";
      code = "NOT_FOUND";
      message = "Requested project file or definition was not found.";
    } else if (exception instanceof java.util.concurrent.TimeoutException) {
      category = "TIMEOUT";
      code = "OPERATION_TIMEOUT";
      message = "Operation timed out.";
      retryable = true;
    } else if (exception instanceof UnsupportedOperationException) {
      category = "UNSUPPORTED";
      code = "UNSUPPORTED_OPERATION";
      message = "Requested operation is not supported.";
    } else {
      category = "INTERNAL";
      code = "OPERATION_FAILED";
      message = "Operation failed.";
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("code", code);
    result.put("category", category);
    result.put("message", message);
    result.put("retryable", retryable);
    return result;
  }

  private String safeExceptionMessage(Exception exception) {
    String message = HopXml.redact(String.valueOf(exception.getMessage()));
    String projectRoot = files.root().toString();
    if (!projectRoot.isBlank()) message = message.replace(projectRoot, "<project>");
    return message.length() <= 512 ? message : message.substring(0, 512);
  }

  private String validateDefinition(String path) {
    if (path == null || path.isBlank()) throw new IllegalArgumentException("path is required");
    String lower = path.toLowerCase(Locale.ROOT);
    if (!lower.endsWith(".hpl") && !lower.endsWith(".hwf"))
      throw new IllegalArgumentException("Definition must be an .hpl or .hwf file: " + path);
    return path;
  }

  private void requireExecution() {
    if (!allowExecution)
      throw new SecurityException("Local execution disabled. Restart with --allow-execution.");
  }

  @SuppressWarnings("unchecked")
  private void normalizeExecutionPath(Map<String, Object> result) {
    Object path = result.get("path");
    if (path instanceof String value) {
      try {
        result.put("path", files.relative(Path.of(value)));
      } catch (Exception ignored) {
      }
    }
    Object nested = result.get("result");
    if (nested instanceof Map<?, ?> map) {
      Object nestedPath = map.get("path");
      if (nestedPath instanceof String value) {
        try {
          ((Map<String, Object>) map).put("path", files.relative(Path.of(value)));
        } catch (Exception ignored) {
        }
      }
    }
  }

  @FunctionalInterface
  private interface CheckedMap {
    Map<String, Object> call() throws Exception;
  }

  @Override
  public void close() {
    executionManager.close();
  }
}
