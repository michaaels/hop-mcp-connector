package io.github.michaaels.hop.mcp;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.json.schema.jackson3.JacksonJsonSchemaValidatorSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

final class HopMcpServer implements AutoCloseable {
  @FunctionalInterface
  interface Handler {
    Map<String, Object> call(Map<String, Object> args) throws Exception;
  }

  private final TrackingInputStream input;
  private final McpSyncServer server;
  private final HopMcpService service;

  HopMcpServer(HopMcpService service, InputStream in, OutputStream protocolOut) {
    this.service = service;
    input = new TrackingInputStream(in);
    var mapper = new JacksonMcpJsonMapperSupplier().get();
    var transport = new StdioServerTransportProvider(mapper, input, protocolOut);
    server =
        McpServer.sync(transport)
            .jsonMapper(mapper)
            .jsonSchemaValidator(new JacksonJsonSchemaValidatorSupplier().get())
            .serverInfo("apache-hop-mcp", "0.9.0")
            .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
            .instructions(
                "Apache Hop project analysis with explicitly authorized local execution and native semantic mutation. Mutations use preview, SHA-256 preconditions, backup, atomic replace, native reload validation and rollback.")
            .build();
    add(
        "hop_config",
        "Show MCP project root, limits and security mode.",
        schema(Map.of(), List.of()),
        a -> service.config());
    add(
        "hop_capabilities",
        "Describe the native semantic operation contract, safety guarantees, Hop compatibility and live-UI availability.",
        schema(Map.of(), List.of()),
        a -> service.capabilities());
    add(
        "hop_live_ui_status",
        "Show active live-UI sessions and recent delivery acknowledgements without exposing session identifiers.",
        schema(
            Map.of(
                "transaction_id",
                str("Optional mutation transaction ID used to filter acknowledgements")),
            List.of()),
        a -> service.liveUiStatus(sDefault(a, "transaction_id", null)));
    add(
        "hop_plugins",
        "Inspect the Apache Hop plugin registry with optional filtering and pagination.",
        schema(
            Map.of(
                "type",
                str("Optional plugin type short name or fully qualified class name"),
                "query",
                str("Optional case-insensitive text in plugin IDs, name, description or category"),
                "offset",
                nonNegativeInteger("Number of matching plugins to skip"),
                "limit",
                integer("Maximum plugins to return")),
            List.of()),
        a ->
            a.isEmpty()
                ? service.plugins()
                : service.plugins(
                    sDefault(a, "type", null),
                    sDefault(a, "query", null),
                    iDefault(a, "offset", 0),
                    iDefault(a, "limit", 50)));
    add(
        "hop_component_types",
        "List native Hop transforms or workflow actions available for semantic authoring.",
        schema(
            Map.of(
                "kind",
                enumStr("pipeline", "workflow"),
                "query",
                str("Optional case-insensitive text in plugin IDs, name, description or category"),
                "offset",
                nonNegativeInteger("Number of matching components to skip"),
                "limit",
                integer("Maximum components to return")),
            List.of("kind")),
        a ->
            service.componentTypes(
                s(a, "kind"),
                sDefault(a, "query", null),
                iDefault(a, "offset", 0),
                iDefault(a, "limit", 50)));
    add(
        "hop_component_schema",
        "Describe safe scalar properties that Hop can inject into a transform or workflow action.",
        schema(
            Map.of(
                "kind", enumStr("pipeline", "workflow"),
                "plugin_id", str("Native Hop transform/action plugin ID")),
            List.of("kind", "plugin_id")),
        a -> service.componentSchema(s(a, "kind"), s(a, "plugin_id")));
    add(
        "hop_catalog",
        "Catalog bounded project files with paths, kinds, sizes and SHA-256 fingerprints, without returning contents.",
        schema(
            Map.of(
                "glob",
                str("Optional case-insensitive glob, default **"),
                "offset",
                nonNegativeInteger("Number of matching files to skip"),
                "limit",
                catalogLimit("Maximum files to return")),
            List.of()),
        a ->
            service.catalog(
                sDefault(a, "glob", "**"), iDefault(a, "offset", 0), iDefault(a, "limit", 100)));
    add(
        "hop_list_definitions",
        "List .hpl pipelines and .hwf workflows under the project root.",
        schema(Map.of(), List.of()),
        a -> service.listDefinitions());
    add(
        "hop_inspect",
        "Inspect a Hop pipeline/workflow structure, SQL tables and references.",
        schema(Map.of("path", str("Project-relative .hpl/.hwf path")), List.of("path")),
        a -> service.inspect(s(a, "path")));
    add(
        "hop_context",
        "Build a consolidated safe context containing structure, validation and project-local dependencies.",
        schema(Map.of("path", str("Project-relative .hpl/.hwf path")), List.of("path")),
        a -> service.context(s(a, "path")));
    add(
        "hop_component",
        "Inspect one transform/action; secret-looking fields are redacted.",
        schema(
            Map.of("path", str("Definition path"), "component", str("Transform or action name")),
            List.of("path", "component")),
        a -> service.component(s(a, "path"), s(a, "component")));
    add(
        "hop_component_lineage",
        "Traverse Hop edges upstream or downstream.",
        schema(
            Map.of(
                "path",
                str("Definition path"),
                "component",
                str("Start component"),
                "direction",
                enumStr("upstream", "downstream"),
                "max_depth",
                integer("Maximum traversal depth, default 10")),
            List.of("path", "component")),
        a ->
            service.lineage(
                s(a, "path"),
                s(a, "component"),
                sDefault(a, "direction", "downstream"),
                iDefault(a, "max_depth", 10)));
    add(
        "hop_validate",
        "Run safe structural validation without field/database resolution.",
        schema(Map.of("path", str("Definition path")), List.of("path")),
        a -> service.validate(s(a, "path")));
    add(
        "hop_deep_check",
        "Run Apache Hop's native checker. Disabled unless hop mcp starts with --allow-deep-check; may access external systems.",
        schema(Map.of("path", str("Definition path")), List.of("path")),
        a -> service.deepCheck(s(a, "path")));
    add(
        "hop_test_definition",
        "Run a gated validate/check/execute cycle and return normalized diagnostics plus advisory semantic correction candidates. Never applies changes.",
        schema(
            Map.of(
                "path",
                str("Project-relative .hpl/.hwf path"),
                "deep_check",
                bool("Run the opt-in native Hop checker, default false"),
                "execute",
                bool("Execute locally only after requested validation phases pass, default false"),
                "run_configuration",
                str("Local run configuration name, default local"),
                "parameters",
                stringMapSchema("Optional named parameter values"),
                "timeout_seconds",
                executionTimeout("Maximum execution time in seconds, default 120")),
            List.of("path")),
        a ->
            service.testDefinition(
                s(a, "path"),
                boolDefault(a, "deep_check", false),
                boolDefault(a, "execute", false),
                sDefault(a, "run_configuration", "local"),
                stringMap(a.get("parameters"), "parameters"),
                iDefault(a, "timeout_seconds", 120)));
    add(
        "hop_read_text",
        "Read a UTF-8 project file, confined to project root and size limits.",
        schema(Map.of("path", str("Project-relative path")), List.of("path")),
        a -> service.readText(s(a, "path")));
    add(
        "hop_search",
        "Search text within the project with scan/result limits.",
        schema(
            Map.of("query", str("Case-insensitive text"), "glob", str("Optional glob, default **")),
            List.of("query")),
        a -> service.search(s(a, "query"), sDefault(a, "glob", "**")));
    add(
        "hop_find_table",
        "Find SQL table references across Hop definitions.",
        schema(Map.of("table", str("Table name or substring")), List.of("table")),
        a -> service.findTable(s(a, "table")));
    add(
        "hop_dependencies",
        "Extract referenced .hpl/.hwf definitions and resolve those inside project root.",
        schema(Map.of("path", str("Definition path")), List.of("path")),
        a -> service.dependencies(s(a, "path")));
    add(
        "hop_execute",
        "Execute one local pipeline/workflow with a bounded timeout. Disabled unless started with --allow-execution.",
        schema(
            Map.of(
                "path",
                str("Project-relative .hpl/.hwf path"),
                "run_configuration",
                str("Local run configuration name, default local"),
                "parameters",
                stringMapSchema("Optional named parameter values"),
                "timeout_seconds",
                executionTimeout("Maximum execution time in seconds, default 120")),
            List.of("path")),
        a ->
            service.execute(
                s(a, "path"),
                sDefault(a, "run_configuration", "local"),
                stringMap(a.get("parameters"), "parameters"),
                iDefault(a, "timeout_seconds", 120)));
    add(
        "hop_start_execution",
        "Start a bounded local pipeline/workflow execution asynchronously.",
        schema(
            Map.of(
                "path",
                str("Project-relative .hpl/.hwf path"),
                "run_configuration",
                str("Local run configuration name, default local"),
                "parameters",
                stringMapSchema("Optional named parameter values"),
                "timeout_seconds",
                executionTimeout("Maximum execution time in seconds, default 120")),
            List.of("path")),
        a ->
            service.startExecution(
                s(a, "path"),
                sDefault(a, "run_configuration", "local"),
                stringMap(a.get("parameters"), "parameters"),
                iDefault(a, "timeout_seconds", 120)));
    add(
        "hop_execution_status",
        "Get the state and result of an asynchronous execution.",
        schema(Map.of("operation_id", str("Execution operation ID")), List.of("operation_id")),
        a -> service.executionStatus(s(a, "operation_id")));
    add(
        "hop_stop_execution",
        "Request cancellation of an asynchronous execution.",
        schema(Map.of("operation_id", str("Execution operation ID")), List.of("operation_id")),
        a -> service.stopExecution(s(a, "operation_id")));
    add(
        "hop_logs",
        "Read a bounded page of redacted events from Hop's logging buffer.",
        schema(
            Map.of(
                "channel_id",
                str("Optional execution log channel ID"),
                "include_general",
                bool("Include general log messages, default true"),
                "from",
                logCursor("First log cursor, default last 200"),
                "to",
                logCursor("Last log cursor, default current")),
            List.of()),
        a ->
            service.logs(
                sDefault(a, "channel_id", null),
                boolDefault(a, "include_general", true),
                iDefault(a, "from", -1),
                iDefault(a, "to", 0)));
    add(
        "hop_prepare_correction_plan",
        "Validate and retain an immutable single-use semantic correction plan. This only previews changes and never writes the definition.",
        schema(
            Map.of(
                "path",
                str("Project-relative .hpl/.hwf path"),
                "kind",
                enumStr("pipeline", "workflow"),
                "operations",
                operationsSchema()),
            List.of("path", "operations")),
        a ->
            service.prepareCorrectionPlan(
                s(a, "path"), sDefault(a, "kind", null), operations(a.get("operations"))));
    add(
        "hop_apply_correction_plan",
        "Apply one prepared correction plan through the transactional native mutator. Requires --allow-mutation and the plan SHA-256.",
        schema(
            Map.of(
                "plan_id",
                str("Session correction plan ID"),
                "plan_sha256",
                str("SHA-256 returned when the plan was prepared")),
            List.of("plan_id", "plan_sha256")),
        a -> service.applyCorrectionPlan(s(a, "plan_id"), s(a, "plan_sha256")));
    add(
        "hop_correction_plan_status",
        "Read the bounded audit status of a correction plan retained in this MCP session.",
        schema(Map.of("plan_id", str("Session correction plan ID")), List.of("plan_id")),
        a -> service.correctionPlanStatus(s(a, "plan_id")));
    add(
        "hop_mutate_definition",
        "Preview or apply transactional native semantic changes to a pipeline/workflow. Call hop_capabilities for the supported operation contract. Existing files require expected_sha256 when apply=true.",
        schema(
            Map.of(
                "path",
                str("Project-relative .hpl/.hwf path"),
                "kind",
                enumStr("pipeline", "workflow"),
                "operations",
                operationsSchema(),
                "expected_sha256",
                str("Current SHA-256; required to apply changes to an existing definition"),
                "apply",
                bool("Apply the mutation; default false")),
            List.of("path", "operations")),
        a ->
            service.mutateDefinition(
                s(a, "path"),
                sDefault(a, "kind", null),
                operations(a.get("operations")),
                sDefault(a, "expected_sha256", null),
                boolValue(a.get("apply"))));
    add(
        "hop_rollback_mutation",
        "Rollback an applied native mutation in this MCP session. The current definition SHA-256 is required.",
        schema(
            Map.of(
                "transaction_id",
                str("Mutation transaction ID"),
                "expected_sha256",
                str("Current definition SHA-256")),
            List.of("transaction_id", "expected_sha256")),
        a -> service.rollbackMutation(s(a, "transaction_id"), s(a, "expected_sha256")));
    add(
        "hop_web_request",
        "Call a configured Hop Web REST endpoint with GET or HEAD. Disabled unless started with --allow-web-api; paths remain inside the configured base URL and sensitive response data is redacted.",
        schema(
            Map.of(
                "method",
                enumStr("GET", "HEAD"),
                "path",
                str("Relative REST path, optionally including a query string"),
                "headers",
                headersSchema()),
            List.of("path")),
        a ->
            service.webRequest(
                sDefault(a, "method", "GET"), s(a, "path"), headers(a.get("headers"))));
  }

  void awaitEof() throws InterruptedException {
    input.awaitEof();
  }

  private void add(String name, String description, Map<String, Object> schema, Handler handler) {
    var tool = McpSchema.Tool.builder(name, schema).description(description).build();
    var spec =
        McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler(
                (exchange, request) -> {
                  try {
                    Map<String, Object> args =
                        request.arguments() == null ? Map.of() : request.arguments();
                    Map<String, Object> data = handler.call(args);
                    return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(JsonUtil.toJson(data))))
                        .structuredContent(data)
                        .build();
                  } catch (Exception e) {
                    Map<String, Object> error =
                        Map.of(
                            "error",
                            e.getClass().getSimpleName(),
                            "message",
                            HopXml.redact(String.valueOf(e.getMessage())));
                    return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(JsonUtil.toJson(error))))
                        .structuredContent(error)
                        .isError(true)
                        .build();
                  }
                })
            .build();
    server.addTool(spec);
  }

  private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("type", "object");
    out.put("properties", properties);
    out.put("additionalProperties", false);
    if (!required.isEmpty()) out.put("required", required);
    return out;
  }

  private static Map<String, Object> str(String d) {
    return Map.of("type", "string", "description", d);
  }

  private static Map<String, Object> integer(String d) {
    return Map.of("type", "integer", "description", d, "minimum", 1, "maximum", 50);
  }

  private static Map<String, Object> enumStr(String... v) {
    return Map.of("type", "string", "enum", List.of(v));
  }

  private static Map<String, Object> nonNegativeInteger(String d) {
    return Map.of(
        "type", "integer", "description", d, "minimum", 0, "maximum", ProjectFiles.MAX_SCAN_FILES);
  }

  private static Map<String, Object> catalogLimit(String d) {
    return Map.of("type", "integer", "description", d, "minimum", 1, "maximum", 200);
  }

  private static Map<String, Object> bool(String d) {
    return Map.of("type", "boolean", "description", d);
  }

  private static Map<String, Object> executionTimeout(String d) {
    return Map.of(
        "type",
        "integer",
        "description",
        d,
        "minimum",
        1,
        "maximum",
        HopNative.MAX_EXECUTION_TIMEOUT_SECONDS);
  }

  private static Map<String, Object> logCursor(String d) {
    return Map.of("type", "integer", "description", d, "minimum", -1);
  }

  private static Map<String, Object> stringMapSchema(String d) {
    return Map.of(
        "type",
        "object",
        "description",
        d,
        "additionalProperties",
        Map.of("type", "string"),
        "maxProperties",
        100);
  }

  private static Map<String, Object> operationsSchema() {
    Map<String, Object> item =
        schema(
            Map.ofEntries(
                Map.entry(
                    "operation",
                    enumStr(HopSemanticCapabilities.OPERATION_NAMES.toArray(String[]::new))),
                Map.entry("value", str("Value for set_name or set_description")),
                Map.entry("plugin_id", str("Native Hop plugin ID for add_component")),
                Map.entry("name", str("New transform/action name for add_component")),
                Map.entry(
                    "properties",
                    componentPropertiesSchema(
                        "Safe scalar properties returned by hop_component_schema")),
                Map.entry("property_groups", componentPropertyGroupsSchema()),
                Map.entry("component", str("Existing transform/action name")),
                Map.entry("new_name", str("New transform/action name")),
                Map.entry("from", str("Hop source component")),
                Map.entry("to", str("Hop target component")),
                Map.entry("enabled", bool("Desired hop state")),
                Map.entry("x", coordinate("Canvas X coordinate")),
                Map.entry("y", coordinate("Canvas Y coordinate")),
                Map.entry("evaluation", bool("Workflow hop success/failure evaluation")),
                Map.entry("unconditional", bool("Workflow hop unconditional state"))),
            List.of("operation"));
    return Map.of(
        "type",
        "array",
        "description",
        "Ordered native semantic mutation operations",
        "items",
        item,
        "maxItems",
        HopDefinitionMutator.MAX_OPERATIONS);
  }

  private static Map<String, Object> coordinate(String d) {
    return Map.of("type", "integer", "description", d, "minimum", 0, "maximum", 1_000_000);
  }

  private static Map<String, Object> componentPropertiesSchema(String d) {
    return Map.of(
        "type",
        "object",
        "description",
        d,
        "additionalProperties",
        Map.of("type", List.of("string", "number", "boolean")),
        "maxProperties",
        HopComponentAuthoring.MAX_PROPERTIES);
  }

  private static Map<String, Object> componentPropertyGroupsSchema() {
    Map<String, Object> row =
        Map.of(
            "type",
            "object",
            "additionalProperties",
            Map.of("type", List.of("string", "number", "boolean")),
            "minProperties",
            1,
            "maxProperties",
            HopComponentAuthoring.MAX_PROPERTIES);
    return Map.of(
        "type",
        "object",
        "description",
        "Tabular property groups returned by hop_component_schema",
        "additionalProperties",
        Map.of(
            "type",
            "array",
            "minItems",
            1,
            "maxItems",
            HopComponentAuthoring.MAX_ROWS_PER_GROUP,
            "items",
            row),
        "maxProperties",
        HopComponentAuthoring.MAX_PROPERTY_GROUPS);
  }

  private static Map<String, Object> headersSchema() {
    return Map.of(
        "type",
        "object",
        "description",
        "Optional request headers; authentication headers are managed by MCP.",
        "additionalProperties",
        Map.of("type", "string"),
        "maxProperties",
        32);
  }

  private static Map<String, String> headers(Object value) {
    if (value == null) return Map.of();
    if (!(value instanceof Map<?, ?> map))
      throw new IllegalArgumentException("headers must be an object");
    Map<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null)
        throw new IllegalArgumentException("headers cannot contain null keys or values");
      out.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
    }
    return out;
  }

  private static Map<String, String> stringMap(Object value, String name) {
    if (value == null) return Map.of();
    if (!(value instanceof Map<?, ?> map))
      throw new IllegalArgumentException(name + " must be an object");
    if (map.size() > 100) throw new IllegalArgumentException(name + " cannot exceed 100 entries");
    Map<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (entry.getKey() == null
          || String.valueOf(entry.getKey()).isBlank()
          || entry.getValue() == null)
        throw new IllegalArgumentException(
            name + " must contain non-empty names and non-null values");
      out.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
    }
    return out;
  }

  private static List<Map<String, Object>> operations(Object value) {
    if (!(value instanceof List<?> list))
      throw new IllegalArgumentException("operations must be an array");
    if (list.size() > HopDefinitionMutator.MAX_OPERATIONS)
      throw new IllegalArgumentException(
          "operations cannot exceed " + HopDefinitionMutator.MAX_OPERATIONS + " entries");
    List<Map<String, Object>> out = new java.util.ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> map))
        throw new IllegalArgumentException("each operation must be an object");
      Map<String, Object> operation = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (entry.getKey() == null)
          throw new IllegalArgumentException("operation keys cannot be null");
        operation.put(String.valueOf(entry.getKey()), entry.getValue());
      }
      out.add(operation);
    }
    return out;
  }

  private static boolean boolValue(Object value) {
    if (value == null) return false;
    if (value instanceof Boolean bool) return bool;
    if (value instanceof String text
        && ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)))
      return Boolean.parseBoolean(text);
    throw new IllegalArgumentException("boolean value expected");
  }

  private static boolean boolDefault(Map<String, Object> values, String key, boolean fallback) {
    return values.containsKey(key) ? boolValue(values.get(key)) : fallback;
  }

  private static String s(Map<String, Object> a, String k) {
    Object v = a.get(k);
    if (v == null || String.valueOf(v).isBlank())
      throw new IllegalArgumentException(k + " is required");
    return String.valueOf(v);
  }

  private static String sDefault(Map<String, Object> a, String k, String d) {
    Object v = a.get(k);
    return v == null ? d : String.valueOf(v);
  }

  private static int iDefault(Map<String, Object> a, String k, int d) {
    Object v = a.get(k);
    if (v == null) return d;
    if (v instanceof Number n) return n.intValue();
    return Integer.parseInt(String.valueOf(v));
  }

  @Override
  public void close() {
    try {
      server.closeGracefully();
    } finally {
      service.close();
    }
  }

  private static final class TrackingInputStream extends FilterInputStream {
    private final CountDownLatch eof = new CountDownLatch(1);

    TrackingInputStream(InputStream in) {
      super(in);
    }

    @Override
    public int read() throws IOException {
      int r = super.read();
      if (r < 0) eof.countDown();
      return r;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int r = super.read(b, off, len);
      if (r < 0) eof.countDown();
      return r;
    }

    @Override
    public void close() throws IOException {
      try {
        super.close();
      } finally {
        eof.countDown();
      }
    }

    void awaitEof() throws InterruptedException {
      eof.await();
    }
  }
}
