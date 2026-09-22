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
            .serverInfo("hop-mcp-connector", HopMcpVersion.current())
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
    if (!service.isToolEnabled(name)) return;
    var toolBuilder =
        McpSchema.Tool.builder(name, schema)
            .description(description)
            .annotations(annotations(name));
    Map<String, Object> outputSchema = outputSchemaFor(name);
    if (outputSchema != null) toolBuilder.outputSchema(outputSchema);
    var tool = toolBuilder.build();
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
                    Map<String, Object> error = service.errorPayload(e);
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

  private static McpSchema.ToolAnnotations annotations(String name) {
    boolean readOnly = true;
    boolean destructive = false;
    boolean idempotent = true;
    boolean openWorld = false;
    switch (name) {
      case "hop_deep_check" -> openWorld = true;
      case "hop_execute", "hop_start_execution", "hop_test_definition" -> {
        readOnly = false;
        destructive = true;
        idempotent = false;
        openWorld = true;
      }
      case "hop_stop_execution" -> {
        readOnly = false;
        destructive = true;
        openWorld = true;
      }
      case "hop_prepare_correction_plan" -> {
        readOnly = false;
        idempotent = false;
      }
      case "hop_apply_correction_plan", "hop_mutate_definition", "hop_rollback_mutation" -> {
        readOnly = false;
        destructive = true;
        idempotent = false;
      }
      case "hop_web_request" -> openWorld = true;
      default -> {
        // Local inspection tools do not change project state or contact external systems.
      }
    }
    return McpSchema.ToolAnnotations.builder()
        .readOnlyHint(readOnly)
        .destructiveHint(destructive)
        .idempotentHint(idempotent)
        .openWorldHint(openWorld)
        .build();
  }

  private static Map<String, Object> outputSchemaFor(String name) {
    Map<String, Object> result =
        switch (name) {
          case "hop_config" -> configOutputSchema();
          case "hop_capabilities" -> capabilitiesOutputSchema();
          case "hop_validate" -> validationOutputSchema();
          case "hop_inspect" -> inspectOutputSchema();
          case "hop_catalog" -> catalogOutputSchema();
          case "hop_component_types" -> componentTypesOutputSchema();
          case "hop_component_schema" -> componentSchemaOutputSchema();
          case "hop_execute" -> executionOutputSchema();
          case "hop_execution_status" -> executionStatusOutputSchema();
          case "hop_mutate_definition" -> mutationOutputSchema();
          case "hop_prepare_correction_plan" -> correctionPlanOutputSchema();
          default -> null;
        };
    return result == null ? null : errorAwareOutputSchema(result);
  }

  private static Map<String, Object> configOutputSchema() {
    Map<String, Object> readBytesSchema =
        boundedInteger(0, ProjectFiles.MAX_READ_BYTES, "Maximum project-file read size");
    return schema(
        fields(
            "version", boundedString(64, "Connector version"),
            "project_root", boundedString(4096, "Canonical configured project root"),
            "transport", enumStr("stdio"),
            "read_only", bool("Whether semantic mutation is disabled"),
            "allow_deep_check", bool("Whether native deep checks are authorized"),
            "allow_execution", bool("Whether local execution is authorized"),
            "allow_mutation", bool("Whether semantic mutation is authorized"),
            "allow_web_api", bool("Whether Hop Web requests are authorized"),
            "web_api_configured", bool("Whether a Hop Web base URL is configured"),
            "web_api_base", boundedString(2048, "Configured Hop Web base URL"),
            "max_read_bytes", readBytesSchema,
            "max_scan_files", nonNegativeInteger("Maximum project scan size")),
        List.of(
            "version",
            "project_root",
            "transport",
            "read_only",
            "allow_deep_check",
            "allow_execution",
            "allow_mutation",
            "allow_web_api",
            "web_api_configured",
            "web_api_base",
            "max_read_bytes",
            "max_scan_files"));
  }

  private static Map<String, Object> capabilitiesOutputSchema() {
    Map<String, Object> operation =
        schema(
            fields(
                "operation", boundedString(64, "Semantic operation identifier"),
                "definition_kinds", arrayOf(enumStr("pipeline", "workflow"), 2),
                "required", stringArray(32),
                "optional", stringArray(32),
                "destructive", bool("Whether applying the operation changes a definition")),
            List.of("operation", "definition_kinds", "required", "optional", "destructive"));
    Map<String, Object> testCycle =
        schema(
            fields(
                "phases", arrayOf(enumStr("structural_validation", "deep_check", "execution"), 3),
                "gated", bool("Whether deep checks and execution require authorization"),
                "correction_mode", enumStr("advisory_only"),
                "auto_apply", bool("Whether generated corrections apply automatically")),
            List.of("phases", "gated", "correction_mode", "auto_apply"));
    Map<String, Object> correction =
        schema(
            fields(
                "scope", enumStr("same_mcp_session"),
                "max_retained", nonNegativeInteger("Maximum retained plans"),
                "ttl_seconds", nonNegativeInteger("Plan lifetime in seconds"),
                "single_use", bool("Whether a prepared plan can be applied once"),
                "sha256_bound", bool("Whether the plan is bound to a file hash"),
                "explicit_apply", bool("Whether applying requires a separate request"),
                "auto_apply", bool("Whether a prepared plan applies automatically")),
            List.of(
                "scope",
                "max_retained",
                "ttl_seconds",
                "single_use",
                "sha256_bound",
                "explicit_apply",
                "auto_apply"));
    return schema(
        fields(
            "product", boundedString(128, "Product name"),
            "definition_kinds", arrayOf(enumStr("pipeline", "workflow"), 2),
            "semantic_operations", arrayOf(operation, 32),
            "preview_available", bool("Whether native mutations can be previewed"),
            "apply_enabled", bool("Whether semantic mutation is authorized"),
            "transactional_write", bool("Whether writes are transactional"),
            "sha256_precondition", bool("Whether writes require file hash preconditions"),
            "native_reload_validation", bool("Whether writes are reloaded through Hop APIs"),
            "rollback", enumStr("same_mcp_session"),
            "test_cycle", testCycle,
            "correction_plans", correction,
            "live_ui_available", bool("Whether a live UI event adapter is available"),
            "live_ui_status", enumStr("connected", "headless_not_connected"),
            "live_ui_adapter", enumStr("project_event_bridge"),
            "live_ui_dirty_tab_policy", enumStr("never_overwrite"),
            "live_ui_supported_clients", arrayOf(enumStr("desktop", "web"), 2),
            "tested_hop_versions", stringArray(8)),
        List.of(
            "product",
            "definition_kinds",
            "semantic_operations",
            "preview_available",
            "apply_enabled",
            "transactional_write",
            "sha256_precondition",
            "native_reload_validation",
            "rollback",
            "test_cycle",
            "correction_plans",
            "live_ui_available",
            "live_ui_status",
            "live_ui_adapter",
            "live_ui_dirty_tab_policy",
            "live_ui_supported_clients",
            "tested_hop_versions"));
  }

  private static Map<String, Object> validationOutputSchema() {
    return toolOutputSchema(
        fields(
            "path", boundedString(4096, "Project-relative definition path"),
            "type", enumStr("pipeline", "workflow"),
            "valid", bool("Whether structural validation passed"),
            "errors", stringArray(ProjectFiles.MAX_RESULTS),
            "warnings", stringArray(ProjectFiles.MAX_RESULTS),
            "diagnostics_truncated", bool("Whether the diagnostics limit was reached")),
        List.of("path", "type", "valid", "errors", "warnings", "diagnostics_truncated"));
  }

  private static Map<String, Object> inspectOutputSchema() {
    Map<String, Object> component =
        schema(
            fields(
                "name", boundedString(1024, "Transform or action name"),
                "plugin", boundedString(1024, "Native Hop plugin identifier"),
                "tag", enumStr("transform", "action")),
            List.of("name", "tag"));
    Map<String, Object> hop =
        schema(
            fields(
                "from", boundedString(1024, "Hop source component"),
                "to", boundedString(1024, "Hop target component"),
                "enabled", bool("Whether this Hop edge is enabled")),
            List.of("from", "to"));
    return toolOutputSchema(
        fields(
            "path", boundedString(4096, "Project-relative definition path"),
            "type", enumStr("pipeline", "workflow"),
            "name", boundedString(1024, "Definition name"),
            "components", arrayOf(component, ProjectFiles.MAX_RESULTS),
            "hops", arrayOf(hop, ProjectFiles.MAX_RESULTS),
            "components_truncated", bool("Whether the component list was truncated"),
            "hops_truncated", bool("Whether the Hop edge list was truncated"),
            "component_count", nonNegativeInteger("Returned component count"),
            "hop_count", nonNegativeInteger("Returned Hop edge count"),
            "tables", stringArray(ProjectFiles.MAX_RESULTS),
            "references", stringArray(ProjectFiles.MAX_RESULTS)),
        List.of(
            "path",
            "type",
            "name",
            "components",
            "hops",
            "components_truncated",
            "hops_truncated",
            "component_count",
            "hop_count",
            "tables",
            "references"));
  }

  private static Map<String, Object> catalogOutputSchema() {
    Map<String, Object> file =
        schema(
            fields(
                "path", boundedString(4096, "Project-relative path"),
                "kind", boundedString(32, "Recognized file kind"),
                "extension", boundedString(32, "File extension"),
                "bytes", boundedInteger(0, Long.MAX_VALUE, "File size in bytes"),
                "last_modified_epoch_ms", boundedInteger(0, Long.MAX_VALUE, "Modification time"),
                "sha256", boundedString(64, "SHA-256 digest or empty when skipped"),
                "hash_skipped", bool("Whether the content hash was skipped"),
                "hash_skip_reason", boundedString(128, "Reason the content hash was skipped")),
            List.of(
                "path",
                "kind",
                "extension",
                "bytes",
                "last_modified_epoch_ms",
                "sha256",
                "hash_skipped"));
    return toolOutputSchema(
        fields(
            "glob", boundedString(2048, "Applied project-relative glob"),
            "offset", nonNegativeInteger("First returned match offset"),
            "limit", catalogLimit("Maximum requested files"),
            "scanned", nonNegativeInteger("Files scanned"),
            "scan_limit_reached", bool("Whether the project scan limit was reached"),
            "count", nonNegativeInteger("Matched file count"),
            "returned", boundedInteger(0, 200, "Returned file count"),
            "has_more", bool("Whether another page or scan remains"),
            "files", arrayOf(file, 200)),
        List.of(
            "glob",
            "offset",
            "limit",
            "scanned",
            "scan_limit_reached",
            "count",
            "returned",
            "has_more",
            "files"));
  }

  private static Map<String, Object> componentTypesOutputSchema() {
    return toolOutputSchema(
        fields(
            "kind", enumStr("pipeline", "workflow"),
            "matched_component_count", nonNegativeInteger("Matching plugin count"),
            "returned_component_count", boundedInteger(0, 50, "Returned plugin count"),
            "offset", nonNegativeInteger("First returned plugin offset"),
            "limit", boundedInteger(1, 50, "Maximum requested plugins"),
            "has_more", bool("Whether another page remains"),
            "query", boundedString(1024, "Applied search query"),
            "components", arrayOf(pluginRowSchema(), 50)),
        List.of(
            "kind",
            "matched_component_count",
            "returned_component_count",
            "offset",
            "limit",
            "has_more",
            "components"));
  }

  private static Map<String, Object> componentSchemaOutputSchema() {
    Map<String, Object> property =
        schema(
            fields(
                "key", boundedString(512, "Injectable property key"),
                "description", boundedString(4096, "Translated property description"),
                "java_type", boundedString(512, "Java property type"),
                "value_type", enumStr("string", "boolean", "integer", "number", "enum"),
                "allowed_values", stringArray(128)),
            List.of("key", "description", "java_type", "value_type"));
    Map<String, Object> propertyGroup =
        schema(
            fields(
                "key", boundedString(512, "Tabular group key"),
                "description", boundedString(4096, "Translated group description"),
                "max_rows",
                    boundedInteger(
                        1, HopComponentAuthoring.MAX_ROWS_PER_GROUP, "Maximum supported rows"),
                "properties", arrayOf(property, HopComponentAuthoring.MAX_PROPERTIES)),
            List.of("key", "description", "max_rows", "properties"));
    return toolOutputSchema(
        fields(
            "kind", enumStr("pipeline", "workflow"),
            "plugin", pluginRowSchema(),
            "native_injection_supported", bool("Whether native metadata injection is supported"),
            "scalar_injection_supported", bool("Whether safe scalar injection is supported"),
            "tabular_injection_supported", bool("Whether supported tabular injection is available"),
            "property_count",
                boundedInteger(0, HopComponentAuthoring.MAX_PROPERTIES, "Scalar property count"),
            "properties", arrayOf(property, HopComponentAuthoring.MAX_PROPERTIES),
            "property_group_count",
                boundedInteger(0, HopComponentAuthoring.MAX_PROPERTY_GROUPS, "Tabular group count"),
            "property_groups", arrayOf(propertyGroup, HopComponentAuthoring.MAX_PROPERTY_GROUPS),
            "sensitive_properties_excluded", bool("Whether sensitive properties were excluded"),
            "collection_properties_excluded", bool("Whether collection properties were excluded"),
            "nested_collection_properties_excluded",
                bool("Whether nested collections were excluded"),
            "structural_properties_excluded", bool("Whether structural properties were excluded")),
        List.of(
            "kind",
            "plugin",
            "native_injection_supported",
            "scalar_injection_supported",
            "tabular_injection_supported",
            "property_count",
            "properties",
            "property_group_count",
            "property_groups",
            "sensitive_properties_excluded",
            "collection_properties_excluded",
            "nested_collection_properties_excluded",
            "structural_properties_excluded"));
  }

  private static Map<String, Object> executionOutputSchema() {
    return toolOutputSchema(
        executionResultFields(),
        List.of(
            "kind",
            "path",
            "run_configuration",
            "ok",
            "timed_out",
            "error_count",
            "status",
            "log_channel_id",
            "started_at",
            "finished_at",
            "diagnostics"));
  }

  private static Map<String, Object> executionStatusOutputSchema() {
    return toolOutputSchema(
        fields(
            "operation_id", boundedString(64, "Execution operation ID"),
            "path", boundedString(4096, "Project-relative definition path"),
            "run_configuration", boundedString(256, "Hop run configuration"),
            "state", enumStr("running", "completed", "timed_out", "failed", "stopped", "stopping"),
            "started_at",
                boundedInteger(0, Long.MAX_VALUE, "Execution start time in epoch milliseconds"),
            "active_executions", nonNegativeInteger("Currently active executions"),
            "finished_at",
                boundedInteger(0, Long.MAX_VALUE, "Execution finish time in epoch milliseconds"),
            "log_channel_id", boundedString(128, "Hop log channel identifier"),
            "result", executionResultSchema(),
            "error", executionErrorSchema(),
            "stop_requested", bool("Whether cancellation was requested")),
        List.of(
            "operation_id",
            "path",
            "run_configuration",
            "state",
            "started_at",
            "active_executions"));
  }

  private static Map<String, Object> mutationOutputSchema() {
    return toolOutputSchema(
        mutationFields(),
        List.of(
            "path",
            "kind",
            "target_exists",
            "preview",
            "applied",
            "changed",
            "old_sha256",
            "new_sha256",
            "before",
            "after",
            "changes",
            "native_reload_valid",
            "backup",
            "transaction_id",
            "rollback_available"));
  }

  private static Map<String, Object> correctionPlanOutputSchema() {
    return toolOutputSchema(
        fields(
            "plan_id",
            boundedString(64, "Session correction plan ID"),
            "plan_sha256",
            boundedString(64, "Immutable plan SHA-256"),
            "state",
            enumStr("prepared"),
            "path",
            boundedString(4096, "Project-relative definition path"),
            "kind",
            enumStr("pipeline", "workflow"),
            "target_exists",
            bool("Whether a definition existed when the plan was prepared"),
            "bound_sha256",
            boundedString(64, "SHA-256 of the original definition or empty"),
            "operation_count",
            boundedInteger(0, HopDefinitionMutator.MAX_OPERATIONS, "Number of operations"),
            "created_at",
            boundedString(64, "Creation time in RFC 3339 format"),
            "expires_at",
            boundedString(64, "Expiry time in RFC 3339 format"),
            "single_use",
            bool("Whether the correction plan can be applied once"),
            "auto_apply",
            bool("Whether the correction plan applies automatically"),
            "preview",
            mutationSuccessSchema()),
        List.of(
            "plan_id",
            "plan_sha256",
            "state",
            "path",
            "kind",
            "target_exists",
            "bound_sha256",
            "operation_count",
            "created_at",
            "expires_at",
            "single_use",
            "auto_apply",
            "preview"));
  }

  private static Map<String, Object> executionResultFields() {
    return fields(
        "kind", enumStr("pipeline", "workflow"),
        "path", boundedString(4096, "Project-relative definition path"),
        "run_configuration", boundedString(256, "Hop run configuration"),
        "ok", bool("Whether execution succeeded"),
        "timed_out", bool("Whether execution reached its timeout"),
        "error_count", nonNegativeInteger("Hop error count"),
        "status", boundedString(256, "Hop execution status"),
        "log_channel_id", boundedString(128, "Hop log channel identifier"),
        "started_at",
            boundedInteger(0, Long.MAX_VALUE, "Execution start time in epoch milliseconds"),
        "finished_at",
            boundedInteger(0, Long.MAX_VALUE, "Execution finish time in epoch milliseconds"),
        "diagnostics", boundedString(512, "Pointer to bounded Hop log output"));
  }

  private static Map<String, Object> executionResultSchema() {
    return schema(
        executionResultFields(),
        List.of(
            "kind",
            "path",
            "run_configuration",
            "ok",
            "timed_out",
            "error_count",
            "status",
            "log_channel_id",
            "started_at",
            "finished_at",
            "diagnostics"));
  }

  private static Map<String, Object> executionErrorSchema() {
    return schema(
        fields(
            "error", boundedString(128, "Execution exception type"),
            "message", boundedString(4096, "Sanitized execution error message")),
        List.of("error", "message"));
  }

  private static Map<String, Object> mutationFields() {
    return fields(
        "path", boundedString(4096, "Project-relative definition path"),
        "kind", enumStr("pipeline", "workflow"),
        "target_exists", bool("Whether a definition existed before preview"),
        "preview", bool("Whether this response is preview only"),
        "applied", bool("Whether the definition was written"),
        "changed", bool("Whether serialized definition content differs"),
        "old_sha256", boundedString(64, "Previous definition SHA-256"),
        "new_sha256", boundedString(64, "Proposed definition SHA-256"),
        "before", definitionSummarySchema(),
        "after", definitionSummarySchema(),
        "changes", arrayOf(semanticChangeSchema(), HopDefinitionMutator.MAX_OPERATIONS),
        "native_reload_valid", bool("Whether native Hop reload validation succeeded"),
        "backup", boundedString(4096, "Backup path or empty when not applicable"),
        "transaction_id", boundedString(64, "Rollback transaction ID or empty when not applied"),
        "rollback_available", bool("Whether rollback is available in this session"),
        "semantic_event_published", bool("Whether a semantic UI event was accepted"));
  }

  private static Map<String, Object> mutationSuccessSchema() {
    return schema(
        mutationFields(),
        List.of(
            "path",
            "kind",
            "target_exists",
            "preview",
            "applied",
            "changed",
            "old_sha256",
            "new_sha256",
            "before",
            "after",
            "changes",
            "native_reload_valid",
            "backup",
            "transaction_id",
            "rollback_available"));
  }

  private static Map<String, Object> definitionSummarySchema() {
    return schema(
        fields(
            "name", boundedString(1024, "Definition name"),
            "description_present", bool("Whether a description is set"),
            "component_count", nonNegativeInteger("Transform or action count"),
            "hop_count", nonNegativeInteger("Hop edge count")),
        List.of("name", "description_present", "component_count", "hop_count"));
  }

  private static Map<String, Object> semanticChangeSchema() {
    Map<String, Object> item =
        schema(
            fields(
                "operation",
                enumStr(HopSemanticCapabilities.OPERATION_NAMES.toArray(String[]::new))),
            List.of("operation"));
    item.put("additionalProperties", boundedChangeValueSchema());
    item.put("maxProperties", 8);
    return item;
  }

  private static Map<String, Object> boundedChangeValueSchema() {
    Map<String, Object> scalar =
        Map.of(
            "anyOf",
            List.of(
                boundedString(8192, "Semantic change value"),
                boundedInteger(-1_000_000_000L, 1_000_000_000L, "Numeric semantic change value"),
                bool("Boolean semantic change value"),
                Map.of("type", "null")));
    Map<String, Object> object = new LinkedHashMap<>();
    object.put("type", "object");
    object.put("additionalProperties", scalar);
    object.put("maxProperties", HopComponentAuthoring.MAX_PROPERTIES);
    Map<String, Object> rowObject = new LinkedHashMap<>();
    rowObject.put("type", "object");
    rowObject.put("additionalProperties", scalar);
    rowObject.put("maxProperties", HopComponentAuthoring.MAX_PROPERTIES);
    Map<String, Object> array = new LinkedHashMap<>();
    array.put("type", "array");
    array.put("items", Map.of("anyOf", List.of(scalar, rowObject)));
    array.put("maxItems", HopComponentAuthoring.MAX_ROWS_PER_GROUP);
    Map<String, Object> objectValues = new LinkedHashMap<>();
    objectValues.put("type", "object");
    objectValues.put("additionalProperties", Map.of("anyOf", List.of(scalar, array)));
    objectValues.put("maxProperties", HopComponentAuthoring.MAX_PROPERTIES);
    return Map.of("anyOf", List.of(scalar, object, array, objectValues));
  }

  private static Map<String, Object> pluginRowSchema() {
    return schema(
        fields(
            "id", boundedString(512, "Canonical Hop plugin ID"),
            "ids", stringArray(64),
            "name", boundedString(2048, "Plugin display name"),
            "description", boundedString(8192, "Plugin description"),
            "category", boundedString(1024, "Plugin category")),
        List.of("id", "ids", "name", "description", "category"));
  }

  private static Map<String, Object> toolOutputSchema(
      Map<String, Object> properties, List<String> required) {
    return schema(properties, required);
  }

  private static Map<String, Object> errorAwareOutputSchema(Map<String, Object> success) {
    Map<String, Object> error =
        schema(
            fields(
                "code", boundedString(64, "Stable error code"),
                "category",
                    enumStr(
                        "VALIDATION",
                        "AUTHORIZATION",
                        "NOT_FOUND",
                        "CONFLICT",
                        "PRECONDITION_FAILED",
                        "TIMEOUT",
                        "EXECUTION",
                        "UNSUPPORTED",
                        "SECURITY",
                        "INTERNAL"),
                "message", boundedString(1000, "Safe, actionable error message"),
                "retryable", bool("Whether retrying without changes may succeed")),
            List.of("code", "category", "message", "retryable"));
    return Map.of("oneOf", List.of(success, error));
  }

  private static Map<String, Object> boundedInteger(
      long minimum, long maximum, String description) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("type", "integer");
    result.put("description", description);
    result.put("minimum", minimum);
    result.put("maximum", maximum);
    return result;
  }

  private static Map<String, Object> stringArray(int maxItems) {
    return arrayOf(boundedString(4096, "Bounded string value"), maxItems);
  }

  private static Map<String, Object> arrayOf(Map<String, Object> item, int maxItems) {
    return Map.of("type", "array", "items", item, "maxItems", maxItems);
  }

  private static Map<String, Object> fields(Object... nameAndSchemas) {
    if (nameAndSchemas.length % 2 != 0)
      throw new IllegalArgumentException("Schema fields must be key/value pairs");
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < nameAndSchemas.length; i += 2) {
      result.put((String) nameAndSchemas[i], nameAndSchemas[i + 1]);
    }
    return result;
  }

  private static Map<String, Object> boundedString(int maxLength, String description) {
    Map<String, Object> result = new LinkedHashMap<>(str(description));
    result.put("maxLength", maxLength);
    return result;
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
