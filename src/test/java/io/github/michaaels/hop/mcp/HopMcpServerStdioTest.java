package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.JacksonJsonSchemaValidatorSupplier;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
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
import org.apache.hop.pipeline.config.PipelineRunConfiguration;
import org.apache.hop.pipeline.engines.local.LocalPipelineRunConfiguration;
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
    Files.writeString(
        project.resolve("valid.hpl"),
        """
        <?xml version="1.0"?>
        <pipeline><info><name>stdio-contract</name></info><order/></pipeline>
        """);
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
                      false,
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
        assertFalse(tools.contains("hop_deep_check"), tools);
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

        requests.println(toolCall(12, "hop_catalog", "{}"));
        String catalog = readResponse(reader, responses);
        assertSuccessfulToolResponse(catalog, 12);
        assertStructuredOutputConforms(catalog, "hop_catalog", outputSchemas);

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
      assertFalse(tools.contains("hop_deep_check"), tools);
      assertFalse(tools.contains("hop_execute"), tools);
      assertFalse(tools.contains("hop_test_definition"), tools);
      assertFalse(tools.contains("hop_execution_status"), tools);
      assertFalse(tools.contains("hop_component_schema"), tools);
      assertFalse(tools.contains("hop_mutate_definition"), tools);
      assertFalse(tools.contains("hop_web_request"), tools);
      clientOutput.close();
      server.awaitEof();
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
