package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.JacksonJsonSchemaValidatorSupplier;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.config.PipelineRunConfiguration;
import org.apache.hop.pipeline.engines.local.LocalPipelineRunConfiguration;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.apache.hop.pipeline.transforms.dummy.DummyMeta;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HopMcpServerStdioTest {
  private static final int RESPONSE_TIMEOUT_SECONDS = 10;
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final JsonSchemaValidator OUTPUT_VALIDATOR =
      new JacksonJsonSchemaValidatorSupplier().get();

  @TempDir Path project;

  @BeforeAll
  static void initializeHopPlugins() throws Exception {
    HopEnvironment.init();
  }

  @Test
  void completesHandshakeAndServesConsecutiveToolCallsOverStdio() throws Exception {
    assertEquals(System.getProperty("project.version"), HopMcpVersion.current());
    Path validDefinition = project.resolve("valid.hpl");
    PipelineMeta validPipeline = new PipelineMeta();
    validPipeline.setFilename(validDefinition.toString());
    validPipeline.setNameSynchronizedWithFilename(false);
    validPipeline.setName("stdio-contract");
    validPipeline.addTransform(new TransformMeta("Dummy", "Input", new DummyMeta()));
    Files.writeString(validDefinition, validPipeline.getXml(new Variables()));
    MemoryMetadataProvider metadataProvider = new MemoryMetadataProvider();
    PipelineRunConfiguration local = new PipelineRunConfiguration();
    local.setName("local");
    LocalPipelineRunConfiguration localEngine = new LocalPipelineRunConfiguration();
    localEngine.setEnginePluginId("Local");
    localEngine.setEnginePluginName("Local");
    local.setEngineRunConfiguration(localEngine);
    metadataProvider.getSerializer(PipelineRunConfiguration.class).save(local);

    PrintStream originalStdout = System.out;
    ByteArrayOutputStream unexpectedStdout = new ByteArrayOutputStream();
    System.setOut(new PrintStream(unexpectedStdout, true, StandardCharsets.UTF_8));
    try {
      try (PipedInputStream serverInput = new PipedInputStream();
          PipedOutputStream clientOutput = new PipedOutputStream(serverInput);
          PipedInputStream clientInput = new PipedInputStream();
          PipedOutputStream serverOutput = new PipedOutputStream(clientInput);
          BufferedReader responses =
              new BufferedReader(new InputStreamReader(clientInput, StandardCharsets.UTF_8));
          PrintWriter requests = new PrintWriter(clientOutput, true, StandardCharsets.UTF_8);
          ExecutorService reader = Executors.newSingleThreadExecutor();
          HopMcpServer server =
              new HopMcpServer(
                  new HopMcpService(
                      new ProjectFiles(project),
                      new Variables(),
                      metadataProvider,
                      true,
                      true,
                      true,
                      false,
                      null),
                  serverInput,
                  serverOutput)) {
        requests.println(
            """
          {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"stdio-contract-test","version":"1"}}}
          """
                .trim());
        String initialized = readResponse(reader, responses);
        assertResponseId(initialized, 1);
        assertTrue(initialized.contains("\"name\":\"hop-mcp-connector\""));
        assertTrue(initialized.contains("\"protocolVersion\":\"2025-11-25\""));
        assertTrue(initialized.contains("\"version\":\"" + HopMcpVersion.current() + "\""));

        requests.println(
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}");

        requests.println("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
        String tools = readResponse(reader, responses);
        assertResponseId(tools, 2);
        assertTrue(tools.contains("hop_config"), tools);
        assertTrue(tools.contains("hop_validate"), tools);
        assertTrue(tools.contains("hop_component_types"), tools);
        assertTrue(tools.contains("hop_prepare_correction_plan"), tools);
        assertTrue(tools.contains("hop_mutate_definition"), tools);
        assertTrue(tools.contains("hop_test_definition"), tools);
        assertTrue(tools.contains("hop_execute"), tools);
        assertTrue(tools.contains("hop_execution_status"), tools);
        assertTrue(tools.contains("hop_execution_history"), tools);
        assertTrue(tools.contains("hop_execution_detail"), tools);
        assertTrue(tools.contains("hop_execution_children"), tools);
        assertTrue(tools.contains("hop_execution_metrics"), tools);
        assertTrue(tools.contains("hop_diagnose_execution"), tools);
        assertTrue(tools.contains("hop_data_profile"), tools);
        assertTrue(tools.contains("hop_deep_check"), tools);
        assertTrue(tools.contains("hop_schema_compare"), tools);
        assertTrue(tools.contains("hop_definition_diff"), tools);
        assertTrue(tools.contains("hop_impact_analysis"), tools);
        assertTrue(tools.contains("hop_environment_diff"), tools);
        assertTrue(tools.contains("hop_metadata_types"), tools);
        assertTrue(tools.contains("hop_metadata_list"), tools);
        assertTrue(tools.contains("hop_metadata_get"), tools);
        assertTrue(tools.contains("hop_metadata_dependencies"), tools);
        assertTrue(tools.contains("hop_test_connection"), tools);
        assertTrue(tools.contains("hop_resolve_configuration"), tools);
        assertTrue(tools.contains("hop_mutate_definition"), tools);
        assertFalse(tools.contains("hop_web_request"), tools);
        assertTrue(tools.contains("readOnlyHint"), tools);
        assertTrue(tools.contains("outputSchema"), tools);
        Map<String, Map<String, Object>> outputSchemas = extractOutputSchemas(tools);

        requests.println(toolCall(3, "hop_config", "{}"));
        String config = readResponse(reader, responses);
        assertSuccessfulToolResponse(config, 3);
        assertStructuredOutputConforms(config, "hop_config", outputSchemas);
        assertTrue(config.contains("\\\"transport\\\":\\\"stdio\\\""));
        for (String property :
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
                "max_scan_files")) {
          assertTrue(config.contains("\"" + property + "\""), config);
        }

        requests.println(toolCall(60, "hop_metadata_types", "{\"limit\":10}"));
        String metadataTypes = readResponse(reader, responses);
        assertSuccessfulToolResponse(metadataTypes, 60);
        assertStructuredOutputConforms(metadataTypes, "hop_metadata_types", outputSchemas);
        assertTrue(metadataTypes.contains("pipeline-run-configuration"), metadataTypes);

        requests.println(
            toolCall(
                61,
                "hop_metadata_list",
                "{\"type\":\"pipeline-run-configuration\",\"limit\":10}"));
        String metadataList = readResponse(reader, responses);
        assertSuccessfulToolResponse(metadataList, 61);
        assertStructuredOutputConforms(metadataList, "hop_metadata_list", outputSchemas);
        assertTrue(metadataList.contains("local"), metadataList);

        requests.println(
            toolCall(
                62,
                "hop_metadata_get",
                "{\"type\":\"pipeline-run-configuration\",\"name\":\"local\"}"));
        String metadataGet = readResponse(reader, responses);
        assertSuccessfulToolResponse(metadataGet, 62);
        assertStructuredOutputConforms(metadataGet, "hop_metadata_get", outputSchemas);
        assertTrue(metadataGet.contains("redaction_applied"), metadataGet);

        requests.println(
            toolCall(
                63,
                "hop_metadata_dependencies",
                "{\"type\":\"pipeline-run-configuration\",\"name\":\"local\",\"limit\":10}"));
        String metadataDependencies = readResponse(reader, responses);
        assertSuccessfulToolResponse(metadataDependencies, 63);
        assertStructuredOutputConforms(
            metadataDependencies, "hop_metadata_dependencies", outputSchemas);

        requests.println(
            toolCall(
                64,
                "hop_resolve_configuration",
                "{\"path\":\"valid.hpl\",\"run_configuration\":\"local\",\"parameters\":{}}"));
        String resolvedRunConfiguration = readResponse(reader, responses);
        assertSuccessfulToolResponse(resolvedRunConfiguration, 64);
        assertStructuredOutputConforms(
            resolvedRunConfiguration, "hop_resolve_configuration", outputSchemas);
        assertTrue(resolvedRunConfiguration.contains("\"plugin_id\""), resolvedRunConfiguration);

        requests.println(toolCall(4, "hop_validate", "{\"path\":\"valid.hpl\"}"));
        String firstValidation = readResponse(reader, responses);
        assertSuccessfulToolResponse(firstValidation, 4);
        assertStructuredOutputConforms(firstValidation, "hop_validate", outputSchemas);
        assertTrue(firstValidation.contains("\\\"valid\\\":true"));

        requests.println(toolCall(5, "hop_validate", "{\"path\":\"valid.hpl\"}"));
        String secondValidation = readResponse(reader, responses);
        assertSuccessfulToolResponse(secondValidation, 5);
        assertTrue(secondValidation.contains("\\\"valid\\\":true"));

        requests.println(toolCall(10, "hop_capabilities", "{}"));
        String capabilities = readResponse(reader, responses);
        assertSuccessfulToolResponse(capabilities, 10);
        assertStructuredOutputConforms(capabilities, "hop_capabilities", outputSchemas);

        requests.println(toolCall(11, "hop_inspect", "{\"path\":\"valid.hpl\"}"));
        String inspection = readResponse(reader, responses);
        assertSuccessfulToolResponse(inspection, 11);
        assertStructuredOutputConforms(inspection, "hop_inspect", outputSchemas);

        requests.println(toolCall(47, "hop_context", "{\"path\":\"valid.hpl\"}"));
        String context = readResponse(reader, responses);
        assertSuccessfulToolResponse(context, 47);
        assertStructuredOutputConforms(context, "hop_context", outputSchemas);

        requests.println(toolCall(48, "hop_live_ui_status", "{}"));
        String liveUiStatus = readResponse(reader, responses);
        assertSuccessfulToolResponse(liveUiStatus, 48);
        assertStructuredOutputConforms(liveUiStatus, "hop_live_ui_status", outputSchemas);

        requests.println(toolCall(49, "hop_test_definition", "{\"path\":\"valid.hpl\"}"));
        String testDefinition = readResponse(reader, responses);
        assertSuccessfulToolResponse(testDefinition, 49);
        assertStructuredOutputConforms(testDefinition, "hop_test_definition", outputSchemas);

        requests.println(toolCall(50, "hop_deep_check", "{\"path\":\"valid.hpl\"}"));
        String deepCheck = readResponse(reader, responses);
        assertResponseId(deepCheck, 50);
        assertStructuredOutputConforms(deepCheck, "hop_deep_check", outputSchemas);

        requests.println(
            toolCall(
                69,
                "hop_schema_compare",
                "{\"connection\":\"missing-connection\",\"schema\":\"public\",\"table\":\"customers\",\"expected\":[{\"name\":\"id\",\"type\":\"Integer\"}]}"));
        String schemaCompare = readResponse(reader, responses);
        assertResponseId(schemaCompare, 69);
        assertTrue(schemaCompare.contains("\"isError\":true"), schemaCompare);
        assertStructuredOutputConforms(schemaCompare, "hop_schema_compare", outputSchemas);

        requests.println(
            toolCall(
                70,
                "hop_definition_diff",
                "{\"path_a\":\"valid.hpl\",\"path_b\":\"valid.hpl\"}"));
        String definitionDiff = readResponse(reader, responses);
        assertSuccessfulToolResponse(definitionDiff, 70);
        assertStructuredOutputConforms(definitionDiff, "hop_definition_diff", outputSchemas);
        assertTrue(definitionDiff.contains("\"identical\":true"), definitionDiff);

        requests.println(
            toolCall(
                71,
                "hop_impact_analysis",
                "{\"definition\":\"valid.hpl\",\"max_depth\":5,\"max_edges\":10,\"max_results\":10}"));
        String impactAnalysis = readResponse(reader, responses);
        assertSuccessfulToolResponse(impactAnalysis, 71);
        assertStructuredOutputConforms(impactAnalysis, "hop_impact_analysis", outputSchemas);
        assertTrue(impactAnalysis.contains("\"node_count\":1"), impactAnalysis);

        requests.println(
            toolCall(
                72,
                "hop_environment_diff",
                "{\"path_a\":\"valid.hpl\",\"path_b\":\"valid.hpl\",\"run_configuration_a\":\"local\",\"run_configuration_b\":\"local\"}"));
        String environmentDiff = readResponse(reader, responses);
        assertSuccessfulToolResponse(environmentDiff, 72);
        assertStructuredOutputConforms(environmentDiff, "hop_environment_diff", outputSchemas);
        assertTrue(environmentDiff.contains("\"identical\":true"), environmentDiff);

        requests.println(toolCall(12, "hop_catalog", "{}"));
        String catalog = readResponse(reader, responses);
        assertSuccessfulToolResponse(catalog, 12);
        assertStructuredOutputConforms(catalog, "hop_catalog", outputSchemas);

        requests.println(toolCall(40, "hop_list_definitions", "{\"limit\":10}"));
        String definitions = readResponse(reader, responses);
        assertSuccessfulToolResponse(definitions, 40);
        assertStructuredOutputConforms(definitions, "hop_list_definitions", outputSchemas);

        requests.println(
            toolCall(41, "hop_read_text", "{\"path\":\"valid.hpl\",\"max_bytes\":1024}"));
        String readText = readResponse(reader, responses);
        assertSuccessfulToolResponse(readText, 41);
        assertStructuredOutputConforms(readText, "hop_read_text", outputSchemas);

        requests.println(toolCall(42, "hop_search", "{\"query\":\"stdio-contract\",\"limit\":10}"));
        String search = readResponse(reader, responses);
        assertSuccessfulToolResponse(search, 42);
        assertStructuredOutputConforms(search, "hop_search", outputSchemas);

        requests.println(
            toolCall(43, "hop_find_table", "{\"table\":\"table_not_found\",\"limit\":10}"));
        String findTable = readResponse(reader, responses);
        assertSuccessfulToolResponse(findTable, 43);
        assertStructuredOutputConforms(findTable, "hop_find_table", outputSchemas);

        requests.println(toolCall(44, "hop_dependencies", "{\"path\":\"valid.hpl\"}"));
        String dependencies = readResponse(reader, responses);
        assertSuccessfulToolResponse(dependencies, 44);
        assertStructuredOutputConforms(dependencies, "hop_dependencies", outputSchemas);

        requests.println(
            toolCall(
                45,
                "hop_component_lineage",
                "{\"path\":\"valid.hpl\",\"component\":\"start\",\"max_edges\":10}"));
        String lineage = readResponse(reader, responses);
        assertSuccessfulToolResponse(lineage, 45);
        assertStructuredOutputConforms(lineage, "hop_component_lineage", outputSchemas);

        requests.println(toolCall(46, "hop_plugins", "{\"limit\":10}"));
        String plugins = readResponse(reader, responses);
        assertSuccessfulToolResponse(plugins, 46);
        assertStructuredOutputConforms(plugins, "hop_plugins", outputSchemas);

        requests.println(toolCall(51, "hop_logs", "{}"));
        String logs = readResponse(reader, responses);
        assertSuccessfulToolResponse(logs, 51);
        assertStructuredOutputConforms(logs, "hop_logs", outputSchemas);

        requests.println(
            toolCall(52, "hop_component", "{\"path\":\"valid.hpl\",\"component\":\"Input\"}"));
        String component = readResponse(reader, responses);
        assertSuccessfulToolResponse(component, 52);
        assertStructuredOutputConforms(component, "hop_component", outputSchemas);

        requests.println(
            toolCall(
                59,
                "hop_component",
                "{\"path\":\"valid.hpl\",\"component\":\"missing-component\"}"));
        String missingComponent = readResponse(reader, responses);
        assertResponseId(missingComponent, 59);
        assertStructuredOutputConforms(missingComponent, "hop_component", outputSchemas);

        requests.println(
            toolCall(13, "hop_component_types", "{\"kind\":\"pipeline\",\"limit\":5}"));
        String componentTypes = readResponse(reader, responses);
        assertSuccessfulToolResponse(componentTypes, 13);
        assertStructuredOutputConforms(componentTypes, "hop_component_types", outputSchemas);

        requests.println(
            toolCall(14, "hop_mutate_definition", "{\"path\":\"valid.hpl\",\"operations\":[]}"));
        String mutation = readResponse(reader, responses);
        assertSuccessfulToolResponse(mutation, 14);
        assertStructuredOutputConforms(mutation, "hop_mutate_definition", outputSchemas);

        requests.println(
            toolCall(
                15, "hop_prepare_correction_plan", "{\"path\":\"valid.hpl\",\"operations\":[]}"));
        String correctionPlan = readResponse(reader, responses);
        assertSuccessfulToolResponse(correctionPlan, 15);
        assertStructuredOutputConforms(
            correctionPlan, "hop_prepare_correction_plan", outputSchemas);

        Map<String, Object> correctionContent =
            objectValue(
                objectValue(parseObject(correctionPlan).get("result")).get("structuredContent"));
        String planId = (String) correctionContent.get("plan_id");
        String planSha256 = (String) correctionContent.get("plan_sha256");
        requests.println(
            toolCall(53, "hop_correction_plan_status", "{\"plan_id\":\"" + planId + "\"}"));
        String correctionStatus = readResponse(reader, responses);
        assertSuccessfulToolResponse(correctionStatus, 53);
        assertStructuredOutputConforms(
            correctionStatus, "hop_correction_plan_status", outputSchemas);

        requests.println(
            toolCall(
                54,
                "hop_apply_correction_plan",
                "{\"plan_id\":\"" + planId + "\",\"plan_sha256\":\"" + planSha256 + "\"}"));
        String appliedCorrection = readResponse(reader, responses);
        assertSuccessfulToolResponse(appliedCorrection, 54);
        assertStructuredOutputConforms(
            appliedCorrection, "hop_apply_correction_plan", outputSchemas);

        requests.println(
            toolCall(55, "hop_correction_plan_status", "{\"plan_id\":\"" + planId + "\"}"));
        String appliedCorrectionStatus = readResponse(reader, responses);
        assertSuccessfulToolResponse(appliedCorrectionStatus, 55);
        assertStructuredOutputConforms(
            appliedCorrectionStatus, "hop_correction_plan_status", outputSchemas);

        byte[] mutableDefinition =
            "<?xml version=\"1.0\"?><pipeline><info><name>mutation-contract</name></info><order/></pipeline>"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(project.resolve("mutable.hpl"), mutableDefinition);
        String expectedSha256 = ProjectFiles.sha256(mutableDefinition);
        requests.println(
            toolCall(
                56,
                "hop_mutate_definition",
                "{\"path\":\"mutable.hpl\",\"kind\":\"pipeline\",\"expected_sha256\":\""
                    + expectedSha256
                    + "\",\"operations\":[{\"operation\":\"set_description\",\"value\":\"stdio mutation\"}],\"apply\":true}"));
        String appliedMutation = readResponse(reader, responses);
        assertSuccessfulToolResponse(appliedMutation, 56);
        assertStructuredOutputConforms(appliedMutation, "hop_mutate_definition", outputSchemas);
        Map<String, Object> mutationContent =
            objectValue(
                objectValue(parseObject(appliedMutation).get("result")).get("structuredContent"));
        requests.println(
            toolCall(
                57,
                "hop_rollback_mutation",
                "{\"transaction_id\":\""
                    + mutationContent.get("transaction_id")
                    + "\",\"expected_sha256\":\""
                    + mutationContent.get("new_sha256")
                    + "\"}"));
        String rolledBackMutation = readResponse(reader, responses);
        assertSuccessfulToolResponse(rolledBackMutation, 57);
        assertStructuredOutputConforms(rolledBackMutation, "hop_rollback_mutation", outputSchemas);

        requests.println(
            toolCall(
                16, "hop_component_schema", "{\"kind\":\"pipeline\",\"plugin_id\":\"Injector\"}"));
        String componentSchema = readResponse(reader, responses);
        assertSuccessfulToolResponse(componentSchema, 16);
        assertStructuredOutputConforms(componentSchema, "hop_component_schema", outputSchemas);
        assertTrue(
            componentSchema.contains("\\\"tabular_injection_supported\\\":true"), componentSchema);

        requests.println(
            toolCall(17, "hop_execute", "{\"path\":\"valid.hpl\",\"timeout_seconds\":10}"));
        String execution = readResponse(reader, responses);
        assertSuccessfulToolResponse(execution, 17);
        assertStructuredOutputConforms(execution, "hop_execute", outputSchemas);
        assertTrue(execution.contains("\\\"ok\\\":true"), execution);
        assertTrue(execution.contains("\\\"timed_out\\\":false"), execution);

        requests.println(
            toolCall(18, "hop_start_execution", "{\"path\":\"valid.hpl\",\"timeout_seconds\":10}"));
        String startedExecution = readResponse(reader, responses);
        assertSuccessfulToolResponse(startedExecution, 18);
        assertStructuredOutputConforms(startedExecution, "hop_start_execution", outputSchemas);
        Matcher operationId =
            Pattern.compile("\\\"operation_id\\\":\\\"([0-9a-f-]{36})\\\"")
                .matcher(startedExecution);
        assertTrue(operationId.find(), startedExecution);

        String executionStatus = "";
        for (int attempt = 0; attempt < 20; attempt++) {
          requests.println(
              toolCall(
                  19 + attempt,
                  "hop_execution_status",
                  "{\"operation_id\":\"" + operationId.group(1) + "\"}"));
          executionStatus = readResponse(reader, responses);
          assertSuccessfulToolResponse(executionStatus, 19 + attempt);
          assertStructuredOutputConforms(executionStatus, "hop_execution_status", outputSchemas);
          if (executionStatus.contains("\\\"state\\\":\\\"completed\\\"")) break;
          Thread.sleep(100);
        }
        assertTrue(executionStatus.contains("\\\"state\\\":\\\"completed\\\""), executionStatus);

        requests.println(
            toolCall(
                58, "hop_stop_execution", "{\"operation_id\":\"" + operationId.group(1) + "\"}"));
        String stoppedExecution = readResponse(reader, responses);
        assertSuccessfulToolResponse(stoppedExecution, 58);
        assertStructuredOutputConforms(stoppedExecution, "hop_stop_execution", outputSchemas);

        requests.println(
            toolCall(
                65,
                "hop_execution_history",
                "{\"location\":\"missing-location\",\"limit\":10}"));
        String executionHistory = readResponse(reader, responses);
        assertResponseId(executionHistory, 65);
        assertTrue(executionHistory.contains("\"isError\":true"), executionHistory);

        requests.println(
            toolCall(
                66,
                "hop_execution_detail",
                "{\"location\":\"missing-location\",\"execution_id\":\""
                    + operationId.group(1)
                    + "\"}"));
        String executionDetail = readResponse(reader, responses);
        assertResponseId(executionDetail, 66);
        assertTrue(executionDetail.contains("\"isError\":true"), executionDetail);

        requests.println(
            toolCall(
                67,
                "hop_execution_children",
                "{\"location\":\"missing-location\",\"execution_id\":\""
                    + operationId.group(1)
                    + "\"}"));
        String executionChildren = readResponse(reader, responses);
        assertResponseId(executionChildren, 67);
        assertTrue(executionChildren.contains("\"isError\":true"), executionChildren);

        requests.println(
            toolCall(
                68,
                "hop_execution_metrics",
                "{\"location\":\"missing-location\",\"execution_id\":\""
                    + operationId.group(1)
                    + "\"}"));
        String executionMetrics = readResponse(reader, responses);
        assertResponseId(executionMetrics, 68);
        assertTrue(executionMetrics.contains("\"isError\":true"), executionMetrics);

        requests.println(toolCall(6, "hop_validate", "{\"path\":42}"));
        String invalidInput = readResponse(reader, responses);
        assertResponseId(invalidInput, 6);
        assertTrue(invalidInput.contains("\"isError\":true"), invalidInput);

        requests.println(toolCall(7, "no_such_tool", "{}"));
        String unknownTool = readResponse(reader, responses);
        assertResponseId(unknownTool, 7);
        assertTrue(
            unknownTool.contains("\"isError\":true") || unknownTool.contains("\"error\":"),
            unknownTool);

        requests.println(toolCall(8, "hop_read_text", "{\"path\":\"missing.txt\"}"));
        String toolFailure = readResponse(reader, responses);
        assertResponseId(toolFailure, 8);
        assertTrue(toolFailure.contains("\"isError\":true"), toolFailure);
        assertTrue(toolFailure.contains("\\\"category\\\":"), toolFailure);
        assertStructuredOutputConforms(toolFailure, "hop_read_text", outputSchemas);

        requests.println(toolCall(9, "hop_validate", "{\"path\":\"valid.hpl\"}"));
        assertSuccessfulToolResponse(readResponse(reader, responses), 9);
        clientOutput.close();
        server.awaitEof();
      }
    } finally {
      System.setOut(originalStdout);
    }
    assertEquals("", unexpectedStdout.toString(StandardCharsets.UTF_8));
  }

  @Test
  void hidesUnauthorizedToolsFromDiscoveryByDefault() throws Exception {
    try (PipedInputStream serverInput = new PipedInputStream();
        PipedOutputStream clientOutput = new PipedOutputStream(serverInput);
        PipedInputStream clientInput = new PipedInputStream();
        PipedOutputStream serverOutput = new PipedOutputStream(clientInput);
        BufferedReader responses =
            new BufferedReader(new InputStreamReader(clientInput, StandardCharsets.UTF_8));
        PrintWriter requests = new PrintWriter(clientOutput, true, StandardCharsets.UTF_8);
        ExecutorService reader = Executors.newSingleThreadExecutor();
        HopMcpServer server =
            new HopMcpServer(
                new HopMcpService(
                    new ProjectFiles(project),
                    new Variables(),
                    new MemoryMetadataProvider(),
                    false),
                serverInput,
                serverOutput)) {
      requests.println(
          "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"stdio-default-gates-test\",\"version\":\"1\"}}}");
      assertResponseId(readResponse(reader, responses), 1);
      requests.println(
          "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}");
      requests.println("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
      String tools = readResponse(reader, responses);
      assertResponseId(tools, 2);
      assertTrue(tools.contains("hop_config"), tools);
      assertTrue(tools.contains("hop_validate"), tools);
      assertTrue(tools.contains("hop_resolve_configuration"), tools);
      assertFalse(tools.contains("hop_deep_check"), tools);
      assertFalse(tools.contains("hop_schema_compare"), tools);
      assertTrue(tools.contains("hop_definition_diff"), tools);
      assertTrue(tools.contains("hop_impact_analysis"), tools);
      assertTrue(tools.contains("hop_environment_diff"), tools);
      assertFalse(tools.contains("hop_test_connection"), tools);
      assertFalse(tools.contains("hop_execute"), tools);
      assertFalse(tools.contains("hop_test_definition"), tools);
      assertFalse(tools.contains("hop_execution_status"), tools);
      assertFalse(tools.contains("hop_execution_history"), tools);
      assertFalse(tools.contains("hop_execution_detail"), tools);
      assertFalse(tools.contains("hop_execution_children"), tools);
      assertFalse(tools.contains("hop_execution_metrics"), tools);
      assertFalse(tools.contains("hop_diagnose_execution"), tools);
      assertTrue(tools.contains("hop_data_profile"), tools);
      assertFalse(tools.contains("hop_component_schema"), tools);
      assertFalse(tools.contains("hop_mutate_definition"), tools);
      assertFalse(tools.contains("hop_web_request"), tools);
      clientOutput.close();
      server.awaitEof();
    }
  }

  @Test
  void registersAllToolsBeforeAcceptingProtocolRequests() throws Exception {
    try (PipedInputStream serverInput = new PipedInputStream();
        PipedOutputStream clientOutput = new PipedOutputStream(serverInput);
        PipedInputStream clientInput = new PipedInputStream();
        PipedOutputStream serverOutput = new PipedOutputStream(clientInput);
        BufferedReader responses =
            new BufferedReader(new InputStreamReader(clientInput, StandardCharsets.UTF_8));
        PrintWriter requests = new PrintWriter(clientOutput, true, StandardCharsets.UTF_8);
        ExecutorService reader = Executors.newSingleThreadExecutor()) {
      requests.println(
          "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"startup-race-test\",\"version\":\"1\"}}}");
      requests.println(
          "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}");
      requests.println("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");

      try (HopMcpServer server =
          new HopMcpServer(
              new HopMcpService(
                  new ProjectFiles(project), new Variables(), new MemoryMetadataProvider(), false),
              serverInput,
              serverOutput)) {
        assertResponseId(readResponse(reader, responses), 1);
        String toolsResponse = readResponse(reader, responses);
        assertResponseId(toolsResponse, 2);
        assertTrue(toolsResponse.contains("hop_config"), toolsResponse);
        assertTrue(toolsResponse.contains("hop_validate"), toolsResponse);
        assertTrue(toolsResponse.contains("hop_dependencies"), toolsResponse);
        clientOutput.close();
        server.awaitEof();
      }
    }
  }

  @Test
  void validatesWebRequestOutputSchemaOverStdio() throws Exception {
    HttpServer web = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    byte[] body = "credentials=web-secret".getBytes(StandardCharsets.UTF_8);
    web.createContext(
        "/api/status",
        exchange -> {
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    web.start();
    try {
      HopWebClient webClient =
          new HopWebClient(
              "http://127.0.0.1:" + web.getAddress().getPort() + "/api", null, null, null, 5);
      try (PipedInputStream serverInput = new PipedInputStream();
          PipedOutputStream clientOutput = new PipedOutputStream(serverInput);
          PipedInputStream clientInput = new PipedInputStream();
          PipedOutputStream serverOutput = new PipedOutputStream(clientInput);
          BufferedReader responses =
              new BufferedReader(new InputStreamReader(clientInput, StandardCharsets.UTF_8));
          PrintWriter requests = new PrintWriter(clientOutput, true, StandardCharsets.UTF_8);
          ExecutorService reader = Executors.newSingleThreadExecutor();
          HopMcpServer server =
              new HopMcpServer(
                  new HopMcpService(
                      new ProjectFiles(project),
                      new Variables(),
                      new MemoryMetadataProvider(),
                      false,
                      false,
                      false,
                      true,
                      webClient),
                  serverInput,
                  serverOutput)) {
        requests.println(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"web-schema-test\",\"version\":\"1\"}}}");
        assertResponseId(readResponse(reader, responses), 1);
        requests.println(
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}");
        requests.println("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
        String tools = readResponse(reader, responses);
        assertResponseId(tools, 2);
        Map<String, Map<String, Object>> outputSchemas = extractOutputSchemas(tools);

        requests.println(
            toolCall(3, "hop_web_request", "{\"method\":\"GET\",\"path\":\"status\"}"));
        String webResponse = readResponse(reader, responses);
        assertSuccessfulToolResponse(webResponse, 3);
        assertStructuredOutputConforms(webResponse, "hop_web_request", outputSchemas);
        assertFalse(webResponse.contains("web-secret"), webResponse);
        clientOutput.close();
        server.awaitEof();
      }
    } finally {
      web.stop(0);
    }
  }

  private static String toolCall(int id, String name, String arguments) {
    return "{\"jsonrpc\":\"2.0\",\"id\":"
        + id
        + ",\"method\":\"tools/call\",\"params\":{\"name\":\""
        + name
        + "\",\"arguments\":"
        + arguments
        + "}}";
  }

  private static Map<String, Map<String, Object>> extractOutputSchemas(String response)
      throws Exception {
    Map<String, Object> envelope = parseObject(response);
    Map<String, Object> result = objectValue(envelope.get("result"));
    Map<String, Map<String, Object>> schemas = new java.util.HashMap<>();
    for (Object toolValue : (List<?>) result.get("tools")) {
      Map<String, Object> tool = objectValue(toolValue);
      if (tool.get("outputSchema") instanceof Map<?, ?> schema) {
        schemas.put((String) tool.get("name"), objectValue(schema));
      }
    }
    return schemas;
  }

  private static void assertStructuredOutputConforms(
      String response, String toolName, Map<String, Map<String, Object>> outputSchemas)
      throws Exception {
    Map<String, Object> envelope = parseObject(response);
    Map<String, Object> result = objectValue(envelope.get("result"));
    Map<String, Object> structuredContent = objectValue(result.get("structuredContent"));
    Map<String, Object> schema = outputSchemas.get(toolName);
    assertNotNull(schema, "No outputSchema was announced for " + toolName);
    JsonSchemaValidator.ValidationResponse validation =
        OUTPUT_VALIDATOR.validate(schema, structuredContent);
    assertTrue(validation.valid(), toolName + " output schema: " + validation.errorMessage());
  }

  private static Map<String, Object> parseObject(String json) throws Exception {
    return JSON.readValue(json, new TypeReference<>() {});
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> objectValue(Object value) {
    assertTrue(value instanceof Map<?, ?>, "Expected JSON object, got " + value);
    return (Map<String, Object>) value;
  }

  private static String readResponse(ExecutorService reader, BufferedReader responses)
      throws Exception {
    Future<String> response = reader.submit(responses::readLine);
    String line = response.get(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertNotNull(line, "MCP server closed STDIO before returning a response");
    return line;
  }

  private static void assertResponseId(String response, int id) {
    assertTrue(response.startsWith("{\"jsonrpc\":\"2.0\""), response);
    assertTrue(response.contains("\"id\":" + id), response);
  }

  private static void assertSuccessfulToolResponse(String response, int id) {
    assertResponseId(response, id);
    assertTrue(response.contains("\"isError\":false"), response);
  }
}
