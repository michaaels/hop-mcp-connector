package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.JacksonJsonSchemaValidatorSupplier;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.database.DatabaseMeta;
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
  void unansweredStdioReadTimesOutAndCleansUp() throws Exception {
    assertTimeout(
        Duration.ofSeconds(4),
        () -> {
          try (TestPipe pipe = TestPipe.open();
              InputStream input = pipe.input();
              OutputStream output = pipe.output();
              ResponsePump responses = new ResponsePump(input, output, 1)) {
            assertThrows(TimeoutException.class, responses::readResponse);
          }
        });
  }

  @Test
  void earlyStdioCloseFailsPromptly() throws Exception {
    assertTimeout(
        Duration.ofSeconds(4),
        () -> {
          try (TestPipe pipe = TestPipe.open();
              InputStream input = pipe.input();
              OutputStream output = pipe.output();
              ResponsePump responses = new ResponsePump(input, output, 1)) {
            output.close();
            assertThrows(AssertionError.class, responses::readResponse);
          }
        });
  }

  @Test
  void responsesSurviveWriterThreadExitAndHandoff() throws Exception {
    ExecutorService waiting = Executors.newSingleThreadExecutor();
    try (TestPipe pipe = TestPipe.open();
        InputStream input = pipe.input();
        OutputStream output = pipe.output();
        ResponsePump responses = new ResponsePump(input, output)) {
      Future<String> first = waiting.submit(responses::readResponse);
      writeOnTemporaryThread(output, "first\n");
      assertEquals("first", first.get(2, TimeUnit.SECONDS));

      Future<String> next = waiting.submit(responses::readResponse);
      assertThrows(TimeoutException.class, () -> next.get(200, TimeUnit.MILLISECONDS));
      writeOnTemporaryThread(output, "second\n");
      assertEquals("second", next.get(2, TimeUnit.SECONDS));
      output.close();
      assertThrows(AssertionError.class, responses::readResponse);
    } finally {
      waiting.shutdownNow();
      assertTrue(waiting.awaitTermination(2, TimeUnit.SECONDS));
    }
  }

  private static void writeOnTemporaryThread(OutputStream output, String value) throws Exception {
    var write =
        new FutureTask<Void>(
            () -> {
              output.write(value.getBytes(StandardCharsets.UTF_8));
              output.flush();
              return null;
            });
    Thread writer = new Thread(write, "temporary-stdio-writer");
    writer.start();
    try {
      write.get(2, TimeUnit.SECONDS);
      writer.join(TimeUnit.SECONDS.toMillis(2));
      assertFalse(writer.isAlive(), "The response writer must exit before the next read");
    } finally {
      write.cancel(true);
    }
  }

  @Test
  void degradedWorkerReturnsTypedRetryableMcpError() throws Exception {
    MemoryMetadataProvider provider = new MemoryMetadataProvider();
    DatabaseMeta connection = new DatabaseMeta();
    connection.setName("WAREHOUSE");
    provider.getSerializer(DatabaseMeta.class).save(connection);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Future<?> stuck =
        HopDeepCheckExecutor.submit(
            () -> {
              started.countDown();
              while (release.getCount() > 0) {
                try {
                  release.await();
                } catch (InterruptedException ignored) {
                  // Simulate a JDBC driver that has not returned yet.
                }
              }
              return null;
            });
    try {
      assertTrue(started.await(5, TimeUnit.SECONDS));
      HopDeepCheckExecutor.cancel(stuck, true);
      try (TestPipe toServer = TestPipe.open();
          TestPipe toClient = TestPipe.open();
          InputStream serverInput = toServer.input();
          OutputStream clientOutput = toServer.output();
          InputStream clientInput = toClient.input();
          OutputStream serverOutput = toClient.output();
          ResponsePump responses = new ResponsePump(clientInput, serverOutput);
          PrintWriter requests = new PrintWriter(clientOutput, true, StandardCharsets.UTF_8);
          HopMcpServer server =
              new HopMcpServer(
                  new HopMcpService(
                      new ProjectFiles(project),
                      new Variables(),
                      provider,
                      true,
                      false,
                      false,
                      false,
                      null),
                  serverInput,
                  serverOutput)) {
        requests.println(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"degraded-worker-test\",\"version\":\"1\"}}}");
        assertResponseId(responses.readResponse(), 1);
        requests.println(
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}");
        requests.println("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
        Map<String, Map<String, Object>> schemas = extractOutputSchemas(responses.readResponse());
        requests.println(toolCall(3, "hop_test_connection", "{\"name\":\"WAREHOUSE\"}"));
        String response = responses.readResponse();
        assertResponseId(response, 3);
        assertTrue(response.contains("\"isError\":true"), response);
        assertStructuredOutputConforms(response, "hop_test_connection", schemas);
        assertTrue(response.contains("DEEP_CHECK_WORKER_UNHEALTHY"), response);
        assertTrue(response.contains("PRECONDITION_FAILED"), response);
        assertTrue(response.contains("\\\"retryable\\\":true"), response);
        clientOutput.close();
        server.awaitEof();
      }
    } finally {
      release.countDown();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while ("DEGRADED".equals(HopDeepCheckExecutor.metrics().get("health"))
          && System.nanoTime() < deadline) Thread.sleep(10);
    }
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
      try (TestPipe toServer = TestPipe.open();
          TestPipe toClient = TestPipe.open();
          InputStream serverInput = toServer.input();
          OutputStream clientOutput = toServer.output();
          InputStream clientInput = toClient.input();
          OutputStream serverOutput = toClient.output();
          ResponsePump responses = new ResponsePump(clientInput, serverOutput);
          PrintWriter requests = new PrintWriter(clientOutput, true, StandardCharsets.UTF_8);
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
        String initialized = responses.readResponse();
        assertResponseId(initialized, 1);
        assertTrue(initialized.contains("\"name\":\"hop-mcp-connector\""));
        assertTrue(initialized.contains("\"protocolVersion\":\"2025-11-25\""));
        assertTrue(initialized.contains("\"version\":\"" + HopMcpVersion.current() + "\""));

        requests.println(
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}");

        requests.println("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
        String tools = responses.readResponse();
        assertResponseId(tools, 2);
        assertTrue(tools.contains("hop_config"), tools);
        assertTrue(tools.contains("hop_runtime_metrics"), tools);
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
        String config = responses.readResponse();
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
                "max_scan_files",
                "max_regular_files_examined")) {
          assertTrue(config.contains("\"" + property + "\""), config);
        }

        requests.println(toolCall(73, "hop_runtime_metrics", "{}"));
        String runtimeMetrics = responses.readResponse();
        assertSuccessfulToolResponse(runtimeMetrics, 73);
        assertStructuredOutputConforms(runtimeMetrics, "hop_runtime_metrics", outputSchemas);
        assertTrue(runtimeMetrics.contains("project_index"), runtimeMetrics);
        assertTrue(runtimeMetrics.contains("deep_checks"), runtimeMetrics);
        assertTrue(runtimeMetrics.contains("HEALTHY"), runtimeMetrics);
        assertFalse(runtimeMetrics.contains("password"), runtimeMetrics);
        assertFalse(runtimeMetrics.contains("token"), runtimeMetrics);

        requests.println(toolCall(60, "hop_metadata_types", "{\"limit\":10}"));
        String metadataTypes = responses.readResponse();
        assertSuccessfulToolResponse(metadataTypes, 60);
        assertStructuredOutputConforms(metadataTypes, "hop_metadata_types", outputSchemas);
        assertTrue(metadataTypes.contains("pipeline-run-configuration"), metadataTypes);

        requests.println(
            toolCall(
                61, "hop_metadata_list", "{\"type\":\"pipeline-run-configuration\",\"limit\":10}"));
        String metadataList = responses.readResponse();
        assertSuccessfulToolResponse(metadataList, 61);
        assertStructuredOutputConforms(metadataList, "hop_metadata_list", outputSchemas);
        assertTrue(metadataList.contains("local"), metadataList);

        requests.println(
            toolCall(
                62,
                "hop_metadata_get",
                "{\"type\":\"pipeline-run-configuration\",\"name\":\"local\"}"));
        String metadataGet = responses.readResponse();
        assertSuccessfulToolResponse(metadataGet, 62);
        assertStructuredOutputConforms(metadataGet, "hop_metadata_get", outputSchemas);
        assertTrue(metadataGet.contains("redaction_applied"), metadataGet);

        requests.println(
            toolCall(
                63,
                "hop_metadata_dependencies",
                "{\"type\":\"pipeline-run-configuration\",\"name\":\"local\",\"limit\":10}"));
        String metadataDependencies = responses.readResponse();
        assertSuccessfulToolResponse(metadataDependencies, 63);
        assertStructuredOutputConforms(
            metadataDependencies, "hop_metadata_dependencies", outputSchemas);

        requests.println(
            toolCall(
                64,
                "hop_resolve_configuration",
                "{\"path\":\"valid.hpl\",\"run_configuration\":\"local\",\"parameters\":{}}"));
        String resolvedRunConfiguration = responses.readResponse();
        assertSuccessfulToolResponse(resolvedRunConfiguration, 64);
        assertStructuredOutputConforms(
            resolvedRunConfiguration, "hop_resolve_configuration", outputSchemas);
        assertTrue(resolvedRunConfiguration.contains("\"plugin_id\""), resolvedRunConfiguration);

        requests.println(toolCall(4, "hop_validate", "{\"path\":\"valid.hpl\"}"));
        String firstValidation = responses.readResponse();
        assertSuccessfulToolResponse(firstValidation, 4);
        assertStructuredOutputConforms(firstValidation, "hop_validate", outputSchemas);
        assertTrue(firstValidation.contains("\\\"valid\\\":true"));

        requests.println(toolCall(5, "hop_validate", "{\"path\":\"valid.hpl\"}"));
        String secondValidation = responses.readResponse();
        assertSuccessfulToolResponse(secondValidation, 5);
        assertTrue(secondValidation.contains("\\\"valid\\\":true"));

        requests.println(toolCall(10, "hop_capabilities", "{}"));
        String capabilities = responses.readResponse();
        assertSuccessfulToolResponse(capabilities, 10);
        assertStructuredOutputConforms(capabilities, "hop_capabilities", outputSchemas);

        requests.println(toolCall(11, "hop_inspect", "{\"path\":\"valid.hpl\"}"));
        String inspection = responses.readResponse();
        assertSuccessfulToolResponse(inspection, 11);
        assertStructuredOutputConforms(inspection, "hop_inspect", outputSchemas);

        requests.println(toolCall(47, "hop_context", "{\"path\":\"valid.hpl\"}"));
        String context = responses.readResponse();
        assertSuccessfulToolResponse(context, 47);
        assertStructuredOutputConforms(context, "hop_context", outputSchemas);

        requests.println(toolCall(48, "hop_live_ui_status", "{}"));
        String liveUiStatus = responses.readResponse();
        assertSuccessfulToolResponse(liveUiStatus, 48);
        assertStructuredOutputConforms(liveUiStatus, "hop_live_ui_status", outputSchemas);

        requests.println(toolCall(49, "hop_test_definition", "{\"path\":\"valid.hpl\"}"));
        String testDefinition = responses.readResponse();
        assertSuccessfulToolResponse(testDefinition, 49);
        assertStructuredOutputConforms(testDefinition, "hop_test_definition", outputSchemas);

        requests.println(toolCall(50, "hop_deep_check", "{\"path\":\"valid.hpl\"}"));
        String deepCheck = responses.readResponse();
        assertResponseId(deepCheck, 50);
        assertStructuredOutputConforms(deepCheck, "hop_deep_check", outputSchemas);

        requests.println(
            toolCall(
                69,
                "hop_schema_compare",
                "{\"connection\":\"missing-connection\",\"schema\":\"public\",\"table\":\"customers\",\"expected\":[{\"name\":\"id\",\"type\":\"Integer\"}]}"));
        String schemaCompare = responses.readResponse();
        assertResponseId(schemaCompare, 69);
        assertTrue(schemaCompare.contains("\"isError\":true"), schemaCompare);
        assertStructuredOutputConforms(schemaCompare, "hop_schema_compare", outputSchemas);

        requests.println(
            toolCall(
                70, "hop_definition_diff", "{\"path_a\":\"valid.hpl\",\"path_b\":\"valid.hpl\"}"));
        String definitionDiff = responses.readResponse();
        assertSuccessfulToolResponse(definitionDiff, 70);
        assertStructuredOutputConforms(definitionDiff, "hop_definition_diff", outputSchemas);
        assertTrue(definitionDiff.contains("\"identical\":true"), definitionDiff);

        requests.println(
            toolCall(
                71,
                "hop_impact_analysis",
                "{\"definition\":\"valid.hpl\",\"max_depth\":5,\"max_edges\":10,\"max_results\":10}"));
        String impactAnalysis = responses.readResponse();
        assertSuccessfulToolResponse(impactAnalysis, 71);
        assertStructuredOutputConforms(impactAnalysis, "hop_impact_analysis", outputSchemas);
        assertTrue(impactAnalysis.contains("\"node_count\":1"), impactAnalysis);

        requests.println(
            toolCall(
                72,
                "hop_environment_diff",
                "{\"path_a\":\"valid.hpl\",\"path_b\":\"valid.hpl\",\"run_configuration_a\":\"local\",\"run_configuration_b\":\"local\"}"));
        String environmentDiff = responses.readResponse();
        assertSuccessfulToolResponse(environmentDiff, 72);
        assertStructuredOutputConforms(environmentDiff, "hop_environment_diff", outputSchemas);
        assertTrue(environmentDiff.contains("\"identical\":true"), environmentDiff);

        requests.println(toolCall(12, "hop_catalog", "{}"));
        String catalog = responses.readResponse();
        assertSuccessfulToolResponse(catalog, 12);
        assertStructuredOutputConforms(catalog, "hop_catalog", outputSchemas);

        requests.println(toolCall(40, "hop_list_definitions", "{\"limit\":10}"));
        String definitions = responses.readResponse();
        assertSuccessfulToolResponse(definitions, 40);
        assertStructuredOutputConforms(definitions, "hop_list_definitions", outputSchemas);

        requests.println(
            toolCall(41, "hop_read_text", "{\"path\":\"valid.hpl\",\"max_bytes\":1024}"));
        String readText = responses.readResponse();
        assertSuccessfulToolResponse(readText, 41);
        assertStructuredOutputConforms(readText, "hop_read_text", outputSchemas);

        requests.println(toolCall(42, "hop_search", "{\"query\":\"stdio-contract\",\"limit\":10}"));
        String search = responses.readResponse();
        assertSuccessfulToolResponse(search, 42);
        assertStructuredOutputConforms(search, "hop_search", outputSchemas);

        requests.println(
            toolCall(43, "hop_find_table", "{\"table\":\"table_not_found\",\"limit\":10}"));
        String findTable = responses.readResponse();
        assertSuccessfulToolResponse(findTable, 43);
        assertStructuredOutputConforms(findTable, "hop_find_table", outputSchemas);

        requests.println(toolCall(44, "hop_dependencies", "{\"path\":\"valid.hpl\"}"));
        String dependencies = responses.readResponse();
        assertSuccessfulToolResponse(dependencies, 44);
        assertStructuredOutputConforms(dependencies, "hop_dependencies", outputSchemas);

        requests.println(
            toolCall(
                45,
                "hop_component_lineage",
                "{\"path\":\"valid.hpl\",\"component\":\"start\",\"max_edges\":10}"));
        String lineage = responses.readResponse();
        assertSuccessfulToolResponse(lineage, 45);
        assertStructuredOutputConforms(lineage, "hop_component_lineage", outputSchemas);

        requests.println(toolCall(46, "hop_plugins", "{\"limit\":10}"));
        String plugins = responses.readResponse();
        assertSuccessfulToolResponse(plugins, 46);
        assertStructuredOutputConforms(plugins, "hop_plugins", outputSchemas);

        requests.println(toolCall(51, "hop_logs", "{}"));
        String logs = responses.readResponse();
        assertSuccessfulToolResponse(logs, 51);
        assertStructuredOutputConforms(logs, "hop_logs", outputSchemas);

        requests.println(
            toolCall(52, "hop_component", "{\"path\":\"valid.hpl\",\"component\":\"Input\"}"));
        String component = responses.readResponse();
        assertSuccessfulToolResponse(component, 52);
        assertStructuredOutputConforms(component, "hop_component", outputSchemas);

        requests.println(
            toolCall(
                59,
                "hop_component",
                "{\"path\":\"valid.hpl\",\"component\":\"missing-component\"}"));
        String missingComponent = responses.readResponse();
        assertResponseId(missingComponent, 59);
        assertStructuredOutputConforms(missingComponent, "hop_component", outputSchemas);

        requests.println(
            toolCall(13, "hop_component_types", "{\"kind\":\"pipeline\",\"limit\":5}"));
        String componentTypes = responses.readResponse();
        assertSuccessfulToolResponse(componentTypes, 13);
        assertStructuredOutputConforms(componentTypes, "hop_component_types", outputSchemas);

        requests.println(
            toolCall(14, "hop_mutate_definition", "{\"path\":\"valid.hpl\",\"operations\":[]}"));
        String mutation = responses.readResponse();
        assertSuccessfulToolResponse(mutation, 14);
        assertStructuredOutputConforms(mutation, "hop_mutate_definition", outputSchemas);

        requests.println(
            toolCall(
                15, "hop_prepare_correction_plan", "{\"path\":\"valid.hpl\",\"operations\":[]}"));
        String correctionPlan = responses.readResponse();
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
        String correctionStatus = responses.readResponse();
        assertSuccessfulToolResponse(correctionStatus, 53);
        assertStructuredOutputConforms(
            correctionStatus, "hop_correction_plan_status", outputSchemas);

        requests.println(
            toolCall(
                54,
                "hop_apply_correction_plan",
                "{\"plan_id\":\"" + planId + "\",\"plan_sha256\":\"" + planSha256 + "\"}"));
        String appliedCorrection = responses.readResponse();
        assertSuccessfulToolResponse(appliedCorrection, 54);
        assertStructuredOutputConforms(
            appliedCorrection, "hop_apply_correction_plan", outputSchemas);

        requests.println(
            toolCall(55, "hop_correction_plan_status", "{\"plan_id\":\"" + planId + "\"}"));
        String appliedCorrectionStatus = responses.readResponse();
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
        String appliedMutation = responses.readResponse();
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
        String rolledBackMutation = responses.readResponse();
        assertSuccessfulToolResponse(rolledBackMutation, 57);
        assertStructuredOutputConforms(rolledBackMutation, "hop_rollback_mutation", outputSchemas);

        requests.println(
            toolCall(
                16, "hop_component_schema", "{\"kind\":\"pipeline\",\"plugin_id\":\"Injector\"}"));
        String componentSchema = responses.readResponse();
        assertSuccessfulToolResponse(componentSchema, 16);
        assertStructuredOutputConforms(componentSchema, "hop_component_schema", outputSchemas);
        assertTrue(
            componentSchema.contains("\\\"tabular_injection_supported\\\":true"), componentSchema);

        requests.println(
            toolCall(17, "hop_execute", "{\"path\":\"valid.hpl\",\"timeout_seconds\":10}"));
        String execution = responses.readResponse();
        assertSuccessfulToolResponse(execution, 17);
        assertStructuredOutputConforms(execution, "hop_execute", outputSchemas);
        assertTrue(execution.contains("\\\"ok\\\":true"), execution);
        assertTrue(execution.contains("\\\"timed_out\\\":false"), execution);

        // Timeout results retain the native unknown-count sentinel, not a fabricated zero.
        Map<String, Object> timeoutContent =
            new java.util.LinkedHashMap<>(
                objectValue(
                    objectValue(parseObject(execution).get("result")).get("structuredContent")));
        timeoutContent.put("ok", false);
        timeoutContent.put("timed_out", true);
        timeoutContent.put("status", "timeout");
        timeoutContent.put("error_count", -1);
        assertTrue(
            OUTPUT_VALIDATOR.validate(outputSchemas.get("hop_execute"), timeoutContent).valid());
        Map<String, Object> nestedTimeout =
            Map.of(
                "operation_id",
                "00000000-0000-0000-0000-000000000000",
                "path",
                "valid.hpl",
                "run_configuration",
                "local",
                "state",
                "timed_out",
                "started_at",
                0,
                "active_executions",
                0,
                "native_worker_active",
                false,
                "result",
                timeoutContent);
        assertTrue(
            OUTPUT_VALIDATOR
                .validate(outputSchemas.get("hop_execution_status"), nestedTimeout)
                .valid());
        timeoutContent.put("error_count", -2);
        assertFalse(
            OUTPUT_VALIDATOR.validate(outputSchemas.get("hop_execute"), timeoutContent).valid());

        requests.println(
            toolCall(18, "hop_start_execution", "{\"path\":\"valid.hpl\",\"timeout_seconds\":10}"));
        String startedExecution = responses.readResponse();
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
          executionStatus = responses.readResponse();
          assertSuccessfulToolResponse(executionStatus, 19 + attempt);
          assertStructuredOutputConforms(executionStatus, "hop_execution_status", outputSchemas);
          if (executionStatus.contains("\\\"state\\\":\\\"completed\\\"")) break;
          Thread.sleep(100);
        }
        assertTrue(executionStatus.contains("\\\"state\\\":\\\"completed\\\""), executionStatus);

        requests.println(
            toolCall(
                58, "hop_stop_execution", "{\"operation_id\":\"" + operationId.group(1) + "\"}"));
        String stoppedExecution = responses.readResponse();
        assertSuccessfulToolResponse(stoppedExecution, 58);
        assertStructuredOutputConforms(stoppedExecution, "hop_stop_execution", outputSchemas);

        requests.println(
            toolCall(
                65, "hop_execution_history", "{\"location\":\"missing-location\",\"limit\":10}"));
        String executionHistory = responses.readResponse();
        assertResponseId(executionHistory, 65);
        assertTrue(executionHistory.contains("\"isError\":true"), executionHistory);

        requests.println(
            toolCall(
                66,
                "hop_execution_detail",
                "{\"location\":\"missing-location\",\"execution_id\":\""
                    + operationId.group(1)
                    + "\"}"));
        String executionDetail = responses.readResponse();
        assertResponseId(executionDetail, 66);
        assertTrue(executionDetail.contains("\"isError\":true"), executionDetail);

        requests.println(
            toolCall(
                67,
                "hop_execution_children",
                "{\"location\":\"missing-location\",\"execution_id\":\""
                    + operationId.group(1)
                    + "\"}"));
        String executionChildren = responses.readResponse();
        assertResponseId(executionChildren, 67);
        assertTrue(executionChildren.contains("\"isError\":true"), executionChildren);

        requests.println(
            toolCall(
                68,
                "hop_execution_metrics",
                "{\"location\":\"missing-location\",\"execution_id\":\""
                    + operationId.group(1)
                    + "\"}"));
        String executionMetrics = responses.readResponse();
        assertResponseId(executionMetrics, 68);
        assertTrue(executionMetrics.contains("\"isError\":true"), executionMetrics);

        requests.println(toolCall(6, "hop_validate", "{\"path\":42}"));
        String invalidInput = responses.readResponse();
        assertResponseId(invalidInput, 6);
        assertTrue(invalidInput.contains("\"isError\":true"), invalidInput);

        requests.println(toolCall(7, "no_such_tool", "{}"));
        String unknownTool = responses.readResponse();
        assertResponseId(unknownTool, 7);
        assertTrue(
            unknownTool.contains("\"isError\":true") || unknownTool.contains("\"error\":"),
            unknownTool);

        requests.println(toolCall(8, "hop_read_text", "{\"path\":\"missing.txt\"}"));
        String toolFailure = responses.readResponse();
        assertResponseId(toolFailure, 8);
        assertTrue(toolFailure.contains("\"isError\":true"), toolFailure);
        assertTrue(toolFailure.contains("\\\"category\\\":"), toolFailure);
        assertStructuredOutputConforms(toolFailure, "hop_read_text", outputSchemas);

        requests.println(toolCall(9, "hop_validate", "{\"path\":\"valid.hpl\"}"));
        assertSuccessfulToolResponse(responses.readResponse(), 9);
        clientOutput.close();
        server.awaitEof();
      }
    } catch (Exception | AssertionError failure) {
      String captured = HopXml.redact(unexpectedStdout.toString(StandardCharsets.UTF_8));
      failure.addSuppressed(
          new IOException(
              "Captured stdout: " + captured.substring(0, Math.min(16384, captured.length()))));
      throw failure;
    } finally {
      System.setOut(originalStdout);
    }
    assertEquals("", unexpectedStdout.toString(StandardCharsets.UTF_8));
  }

  @Test
  void hidesUnauthorizedToolsFromDiscoveryByDefault() throws Exception {
    try (TestPipe toServer = TestPipe.open();
        TestPipe toClient = TestPipe.open();
        InputStream serverInput = toServer.input();
        OutputStream clientOutput = toServer.output();
        InputStream clientInput = toClient.input();
        OutputStream serverOutput = toClient.output();
        ResponsePump responses = new ResponsePump(clientInput, serverOutput);
        PrintWriter requests = new PrintWriter(clientOutput, true, StandardCharsets.UTF_8);
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
      assertResponseId(responses.readResponse(), 1);
      requests.println(
          "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}");
      requests.println("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
      String tools = responses.readResponse();
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
      assertFalse(tools.contains("hop_data_profile"), tools);
      assertFalse(tools.contains("hop_component_schema"), tools);
      assertFalse(tools.contains("hop_mutate_definition"), tools);
      assertFalse(tools.contains("hop_web_request"), tools);
      clientOutput.close();
      server.awaitEof();
    }
  }

  @Test
  void pipelinedSuccessAndValidationErrorsKeepTheSessionUsable() throws Exception {
    try (TestPipe toServer = TestPipe.open();
        TestPipe toClient = TestPipe.open();
        ResponsePump responses = new ResponsePump(toClient.input(), toClient.output());
        PrintWriter requests = new PrintWriter(toServer.output(), true, StandardCharsets.UTF_8);
        HopMcpServer server =
            new HopMcpServer(
                new HopMcpService(
                    new ProjectFiles(project),
                    new Variables(),
                    new MemoryMetadataProvider(),
                    false,
                    false,
                    false,
                    false,
                    null),
                toServer.input(),
                toClient.output())) {
      requests.println(
          "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"pipelined-test\",\"version\":\"1\"}}}");
      assertResponseId(responses.readResponse(), 1);
      requests.println(
          "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}");
      for (int id = 100; id < 132; id++) {
        requests.println(
            id % 2 == 0
                ? toolCall(id, "hop_config", "{}")
                : toolCall(id, "hop_validate", "{\"path\":42}"));
      }
      java.util.Set<Integer> received = new java.util.HashSet<>();
      for (int index = 0; index < 32; index++) {
        String response = responses.readResponse();
        int id = ((Number) parseObject(response).get("id")).intValue();
        assertTrue(id >= 100 && id < 132, response);
        assertTrue(received.add(id), "Duplicate response ID: " + id);
        if (id % 2 == 0) assertSuccessfulToolResponse(response, id);
        else
          assertEquals(
              Boolean.TRUE, objectValue(parseObject(response).get("result")).get("isError"));
      }
      requests.println(toolCall(1000, "hop_config", "{}"));
      assertSuccessfulToolResponse(responses.readResponse(), 1000);
      toServer.output().close();
      server.awaitEof();
    }
  }

  @Test
  void registersAllToolsBeforeAcceptingProtocolRequests() throws Exception {
    try (TestPipe toServer = TestPipe.open();
        TestPipe toClient = TestPipe.open();
        InputStream serverInput = toServer.input();
        OutputStream clientOutput = toServer.output();
        InputStream clientInput = toClient.input();
        OutputStream serverOutput = toClient.output();
        ResponsePump responses = new ResponsePump(clientInput, serverOutput);
        PrintWriter requests = new PrintWriter(clientOutput, true, StandardCharsets.UTF_8)) {
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
        assertResponseId(responses.readResponse(), 1);
        String toolsResponse = responses.readResponse();
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
    AtomicInteger httpRequests = new AtomicInteger();
    byte[] body = "credentials=web-secret".getBytes(StandardCharsets.UTF_8);
    web.createContext(
        "/",
        exchange -> {
          httpRequests.incrementAndGet();
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    web.start();
    try {
      HopWebClient webClient =
          new HopWebClient(
              "http://127.0.0.1:" + web.getAddress().getPort() + "/api/hop", null, null, null, 5);
      try (TestPipe toServer = TestPipe.open();
          TestPipe toClient = TestPipe.open();
          InputStream serverInput = toServer.input();
          OutputStream clientOutput = toServer.output();
          InputStream clientInput = toClient.input();
          OutputStream serverOutput = toClient.output();
          ResponsePump responses = new ResponsePump(clientInput, serverOutput);
          PrintWriter requests = new PrintWriter(clientOutput, true, StandardCharsets.UTF_8);
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
        assertResponseId(responses.readResponse(), 1);
        requests.println(
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}");
        requests.println("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
        String tools = responses.readResponse();
        assertResponseId(tools, 2);
        Map<String, Map<String, Object>> outputSchemas = extractOutputSchemas(tools);

        requests.println(
            toolCall(3, "hop_web_request", "{\"method\":\"GET\",\"path\":\"status\"}"));
        String webResponse = responses.readResponse();
        assertSuccessfulToolResponse(webResponse, 3);
        assertStructuredOutputConforms(webResponse, "hop_web_request", outputSchemas);
        assertFalse(webResponse.contains("web-secret"), webResponse);
        int responseId = 4;
        for (String path : List.of("startPipeline", "getExecInfo?type=DELETE", "unknownEndpoint")) {
          for (String method : List.of("GET", "HEAD")) {
            requests.println(
                toolCall(
                    responseId,
                    "hop_web_request",
                    JsonUtil.toJson(Map.of("method", method, "path", path))));
            String rejected = responses.readResponse();
            assertResponseId(rejected, responseId++);
            assertEquals(
                Boolean.TRUE, ((Map<?, ?>) parseObject(rejected).get("result")).get("isError"));
            assertTrue(rejected.contains("WEB_ENDPOINT_NOT_READ_ONLY"), rejected);
            assertStructuredOutputConforms(rejected, "hop_web_request", outputSchemas);
          }
        }
        assertEquals(1, httpRequests.get());
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

  /** NIO endpoints remain open when a temporary SDK response-writer thread exits. */
  private record TestPipe(InputStream input, OutputStream output) implements AutoCloseable {
    private static TestPipe open() throws IOException {
      Pipe pipe = Pipe.open();
      return new TestPipe(
          Channels.newInputStream(pipe.source()), Channels.newOutputStream(pipe.sink()));
    }

    @Override
    public void close() throws IOException {
      try (input;
          output) {
        // Close both endpoints, preserving any suppressed cleanup exception.
      }
    }
  }

  private static final class ResponsePump implements AutoCloseable {
    private final InputStream input;
    private final OutputStream output;
    private final BufferedReader lines;
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private final int timeoutSeconds;
    private Future<String> pending;

    private ResponsePump(InputStream input, OutputStream output) {
      this(input, output, RESPONSE_TIMEOUT_SECONDS);
    }

    private ResponsePump(InputStream input, OutputStream output, int timeoutSeconds) {
      this.input = input;
      this.output = output;
      this.timeoutSeconds = timeoutSeconds;
      this.lines = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    }

    private String readResponse() throws Exception {
      pending = reader.submit(lines::readLine);
      try {
        String line = pending.get(timeoutSeconds, TimeUnit.SECONDS);
        assertNotNull(line, "MCP server closed STDIO before returning a response");
        return line;
      } catch (Exception | AssertionError failure) {
        if (failure instanceof TimeoutException) {
          StringBuilder threads = new StringBuilder("STDIO timeout thread stacks:");
          Thread.getAllStackTraces().entrySet().stream()
              .limit(64)
              .forEach(
                  entry -> {
                    Thread thread = entry.getKey();
                    threads
                        .append('\n')
                        .append(thread.getName())
                        .append(' ')
                        .append(thread.getState());
                    java.util.Arrays.stream(entry.getValue())
                        .limit(32)
                        .forEach(frame -> threads.append("\n  ").append(frame));
                  });
          failure.addSuppressed(new IOException(threads.toString()));
        }
        pending.cancel(true);
        try {
          output.close();
        } catch (IOException cleanup) {
          failure.addSuppressed(cleanup);
        }
        try {
          input.close();
        } catch (IOException cleanup) {
          failure.addSuppressed(cleanup);
        }
        throw failure;
      } finally {
        pending = null;
      }
    }

    @Override
    public void close() throws Exception {
      if (pending != null) pending.cancel(true);
      IOException failure = null;
      try {
        output.close();
      } catch (IOException exception) {
        failure = exception;
      }
      try {
        input.close();
      } catch (IOException exception) {
        if (failure == null) failure = exception;
        else failure.addSuppressed(exception);
      }
      reader.shutdownNow();
      if (!reader.awaitTermination(2, TimeUnit.SECONDS)) {
        IOException exception = new IOException("STDIO response reader did not terminate");
        if (failure == null) failure = exception;
        else failure.addSuppressed(exception);
      }
      if (failure != null) throw failure;
    }
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
