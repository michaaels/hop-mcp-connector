package io.github.michaaels.hop.mcp;

import java.nio.charset.StandardCharsets;
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
  private final HopMetadataService metadata;
  private final HopConnectionService connections;
  private final HopSchemaCompareService schemaCompare;
  private final HopDefinitionDiffService definitionDiff;
  private final HopImpactAnalysisService impactAnalysis;
  private final HopEnvironmentDiffService environmentDiff;
  private final HopRunConfigurationService runConfigurations;
  private final HopExecutionRepository executionRepository;
  private final HopDiagnosisService diagnosis;
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
    this.metadata = new HopMetadataService(files, metadataProvider);
    this.connections = new HopConnectionService(metadataProvider, variables, allowDeepCheck);
    this.schemaCompare = new HopSchemaCompareService(metadataProvider, variables, allowDeepCheck);
    this.definitionDiff = new HopDefinitionDiffService(files, variables, metadataProvider);
    this.impactAnalysis = new HopImpactAnalysisService(files, variables);
    this.runConfigurations = new HopRunConfigurationService(metadataProvider, variables);
    this.environmentDiff = new HopEnvironmentDiffService(files, variables, runConfigurations);
    this.executionRepository = new HopExecutionRepository(files, variables, metadataProvider);
    this.diagnosis =
        new HopDiagnosisService(
            files, executionRepository, metadata, runConfigurations, this::logs);
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
    result.put("read_only", !(allowMutation || allowExecution || allowDeepCheck || allowWebApi));
    result.put("definition_write_enabled", allowMutation);
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
      case "hop_deep_check", "hop_test_connection", "hop_schema_compare" -> allowDeepCheck;
      case "hop_test_definition",
              "hop_execute",
              "hop_start_execution",
              "hop_execution_status",
              "hop_execution_history",
              "hop_execution_detail",
              "hop_execution_children",
              "hop_execution_metrics",
              "hop_diagnose_execution",
              "hop_data_profile",
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

  Map<String, Object> metadataTypes(int offset, int limit) throws Exception {
    return metadata.types(offset, limit);
  }

  Map<String, Object> metadataList(String type, String query, int offset, int limit)
      throws Exception {
    return metadata.list(type, query, offset, limit);
  }

  Map<String, Object> metadataGet(String type, String name) throws Exception {
    return metadata.get(type, name);
  }

  Map<String, Object> metadataDependencies(String type, String name, int offset, int limit)
      throws Exception {
    return metadata.dependencies(type, name, offset, limit);
  }

  Map<String, Object> testConnection(String name, int timeoutSeconds) throws Exception {
    return connections.testConnection(name, timeoutSeconds);
  }

  Map<String, Object> testConnection(String type, String name, int timeoutSeconds)
      throws Exception {
    if (type != null && !type.isBlank() && !"rdbms".equalsIgnoreCase(type)) {
      throw new IllegalArgumentException("Only rdbms connection testing is supported");
    }
    return connections.testConnection(name, timeoutSeconds);
  }

  Map<String, Object> schemaCompare(
      String connection,
      String schema,
      String table,
      List<Map<String, Object>> expected,
      int timeoutSeconds)
      throws Exception {
    return schemaCompare.compare(connection, schema, table, expected, timeoutSeconds);
  }

  Map<String, Object> definitionDiff(String pathA, String pathB) throws Exception {
    return definitionDiff.compare(pathA, pathB);
  }

  Map<String, Object> impactAnalysis(
      String table,
      String metadata,
      String definition,
      int maxDepth,
      int maxEdges,
      int maxResults)
      throws Exception {
    return impactAnalysis.analyze(table, metadata, definition, maxDepth, maxEdges, maxResults);
  }

  Map<String, Object> environmentDiff(
      String pathA,
      String pathB,
      String runConfigurationA,
      String runConfigurationB,
      Map<String, String> parametersA,
      Map<String, String> parametersB)
      throws Exception {
    return environmentDiff.compare(
        pathA, pathB, runConfigurationA, runConfigurationB, parametersA, parametersB);
  }

  Map<String, Object> resolveRunConfiguration(String kind, String name) throws Exception {
    return runConfigurations.resolve(kind, name);
  }

  Map<String, Object> resolveConfiguration(
      String path, String runConfiguration, Map<String, String> parameters) throws Exception {
    return runConfigurations.resolveConfiguration(path, runConfiguration, parameters);
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

  Map<String, Object> listDefinitions(int offset, int limit) throws Exception {
    return files.definitionsPage(offset, limit);
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

  Map<String, Object> lineage(
      String path, String component, String direction, int maxDepth, int maxEdges)
      throws Exception {
    Map<String, Object> traversal =
        HopXml.lineageBounded(readDefinition(path), component, direction, maxDepth, maxEdges);
    return Map.of(
        "path",
        path,
        "component",
        component,
        "direction",
        direction,
        "edges",
        traversal.get("edges"),
        "edges_truncated",
        traversal.get("edges_truncated"),
        "visited_nodes",
        traversal.get("visited_nodes"),
        "max_depth_applied",
        traversal.get("max_depth_applied"));
  }

  Map<String, Object> readText(String path, long offset, int maxBytes) throws Exception {
    ProjectFiles.validateTextChunk(offset, maxBytes);
    Path target = files.resolve(path);
    String relative = files.relative(target);
    String redacted = SensitiveData.redactSensitiveText(files.readText(relative));
    return files.textChunk(relative, redacted, offset, maxBytes);
  }

  Map<String, Object> search(String query, String glob, int offset, int limit) throws Exception {
    return files.search(query, glob, offset, limit);
  }

  Map<String, Object> findTable(String table, int offset, int limit) throws Exception {
    if (table == null || table.isBlank()) throw new IllegalArgumentException("table is required");
    if (offset < 0 || offset > ProjectFiles.MAX_SCAN_FILES)
      throw new IllegalArgumentException(
          "offset must be between 0 and " + ProjectFiles.MAX_SCAN_FILES);
    if (limit < 1 || limit > ProjectFiles.MAX_STRUCTURED_RESULTS)
      throw new IllegalArgumentException(
          "limit must be between 1 and " + ProjectFiles.MAX_STRUCTURED_RESULTS);
    List<Map<String, Object>> matches = new ArrayList<>();
    String needle = table.toLowerCase(Locale.ROOT);
    BoundedProjectWalker.ScanResult scan = files.definitionScan(ProjectFiles.MAX_SCAN_FILES);
    long scannedBytes = 0;
    int scannedFiles = 0;
    int matched = 0;
    boolean scanLimitReached = scan.scanLimitReached();
    boolean resultLimitReached = false;
    int tableWindow =
        Math.min(
            ProjectFiles.MAX_SCAN_FILES + ProjectFiles.MAX_STRUCTURED_RESULTS + 1,
            offset + limit + 1);
    searchDefinitions:
    for (Path path : scan.files()) {
      long size;
      try {
        size = java.nio.file.Files.size(path);
      } catch (java.io.IOException | RuntimeException e) {
        scanLimitReached = true;
        continue;
      }
      if (size > ProjectFiles.MAX_READ_BYTES
          || size > ProjectFiles.MAX_TOTAL_SCAN_BYTES - scannedBytes) {
        scanLimitReached = true;
        continue;
      }
      byte[] content;
      try {
        content = files.readBytes(path);
      } catch (java.io.IOException e) {
        scanLimitReached = true;
        continue;
      }
      scannedBytes += content.length;
      scannedFiles++;
      String relative = files.relative(path);
      String definition;
      try {
        definition =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(content))
                .toString();
      } catch (java.nio.charset.CharacterCodingException e) {
        scanLimitReached = true;
        continue;
      }
      for (String found : HopXml.findTables(definition, needle, tableWindow)) {
        matched++;
        if (matched > offset && matches.size() < limit) {
          matches.add(Map.of("path", relative, "table", found));
        } else if (matched > offset + limit) {
          resultLimitReached = true;
          break searchDefinitions;
        }
      }
    }
    boolean resultsTruncated = resultLimitReached || scanLimitReached || scan.resultsTruncated();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("table", table);
    result.put("offset", offset);
    result.put("limit", limit);
    result.put("count", matched);
    result.put("count_complete", !resultsTruncated && !scanLimitReached);
    result.put("returned", matches.size());
    result.put("scanned_files", scannedFiles);
    result.put("visited_entries", scan.visitedEntries());
    result.put("scanned_bytes", scannedBytes);
    result.put("scan_limit_reached", scanLimitReached);
    result.put("result_limit_reached", resultLimitReached);
    result.put("results_truncated", resultsTruncated);
    result.put("has_more", resultLimitReached || scanLimitReached || scan.resultsTruncated());
    result.put("matches", matches);
    return result;
  }

  Map<String, Object> dependencies(String path) throws Exception {
    String xml = readDefinition(path);
    Set<String> refs =
        new LinkedHashSet<>(HopXml.references(xml, ProjectFiles.MAX_STRUCTURED_RESULTS + 1));
    List<Map<String, Object>> resolved = new ArrayList<>();
    boolean truncated = refs.size() > ProjectFiles.MAX_STRUCTURED_RESULTS;
    for (String ref : refs.stream().limit(ProjectFiles.MAX_STRUCTURED_RESULTS).toList()) {
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
    return Map.of(
        "path",
        path,
        "dependencies",
        resolved,
        "count",
        refs.size(),
        "count_complete",
        !truncated,
        "returned",
        resolved.size(),
        "truncated",
        truncated);
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

  Map<String, Object> executionHistory(
      String location,
      String path,
      String status,
      Long fromEpochMs,
      Long toEpochMs,
      int offset,
      int limit)
      throws Exception {
    requireExecution();
    return executionRepository.history(location, path, status, fromEpochMs, toEpochMs, offset, limit);
  }

  Map<String, Object> executionDetail(String location, String executionId) throws Exception {
    requireExecution();
    return executionRepository.detail(location, executionId);
  }

  Map<String, Object> executionChildren(String location, String executionId, int maxDepth, int maxNodes)
      throws Exception {
    requireExecution();
    return executionRepository.children(location, executionId, maxDepth, maxNodes);
  }

  Map<String, Object> executionMetrics(String location, String executionId) throws Exception {
    requireExecution();
    return executionRepository.metrics(location, executionId);
  }

  Map<String, Object> diagnoseExecution(
      String location,
      String executionId,
      String channelId,
      boolean includeGeneral,
      int logFrom,
      int logTo,
      int maxPrevious)
      throws Exception {
    requireExecution();
    return diagnosis.diagnose(
        location, executionId, channelId, includeGeneral, logFrom, logTo, maxPrevious);
  }

  Map<String, Object> dataProfile(
      String location, String executionId, String transform, List<String> fields) throws Exception {
    requireExecution();
    return executionRepository.profile(location, executionId, transform, fields);
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
    int requestedEnd = to <= 0 ? last : Math.min(to, last);
    int end = Math.min(requestedEnd, start + ProjectFiles.MAX_LOG_EVENTS);
    if (start < 0 || end < start) throw new IllegalArgumentException("Invalid log cursor range");
    List<HopLoggingEvent> events =
        HopLogStore.getLogBufferFromTo(
            channelId == null || channelId.isBlank() ? null : List.of(channelId),
            includeGeneral,
            start,
            end);
    List<Map<String, Object>> rows = new ArrayList<>();
    long returnedBytes = 0;
    boolean truncated = end < requestedEnd;
    for (HopLoggingEvent event : events) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("timestamp", event.getTimeStamp());
      row.put("level", event.getLevel() == null ? "" : event.getLevel().getCode());
      String message =
          ProjectFiles.truncate(
              SensitiveData.sanitizeExceptionMessage(String.valueOf(event.getMessage())),
              ProjectFiles.MAX_LOG_MESSAGE_CHARS);
      long eventBytes =
          message.getBytes(StandardCharsets.UTF_8).length
              + String.valueOf(row.get("level")).getBytes(StandardCharsets.UTF_8).length
              + 64;
      if (eventBytes > ProjectFiles.MAX_LOG_BYTES - returnedBytes)
        throw new IllegalStateException("Log page exceeded its bounded byte budget");
      row.put("message", message);
      rows.add(row);
      returnedBytes += eventBytes;
    }
    if (rows.size() < events.size()) truncated = true;
    return Map.of(
        "from",
        start,
        "to",
        end,
        "last_line",
        last,
        "count",
        rows.size(),
        "returned_bytes",
        returnedBytes,
        "truncated",
        truncated,
        "events",
        rows);
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
    if (exception instanceof McpException typed) {
      category = typed.category();
      code = typed.code();
      retryable = typed.retryable();
      message = safeExceptionMessage(exception);
    } else if (exception instanceof SecurityException) {
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
    String message = SensitiveData.sanitizeExceptionMessage(exception);
    if (message == null) message = "Operation failed.";
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
