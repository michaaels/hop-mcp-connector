package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HopMcpServerStdioTest {
  private static final int RESPONSE_TIMEOUT_SECONDS = 10;

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
          """
          {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"stdio-contract-test","version":"1"}}}
          """
              .trim());
      String initialized = readResponse(reader, responses);
      assertResponseId(initialized, 1);
      assertTrue(initialized.contains("\"name\":\"apache-hop-mcp\""));
      assertTrue(initialized.contains("\"version\":\"" + HopMcpVersion.current() + "\""));

      requests.println(
          "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}");

      requests.println(toolCall(2, "hop_config", "{}"));
      String config = readResponse(reader, responses);
      assertSuccessfulToolResponse(config, 2);
      assertTrue(config.contains("\\\"transport\\\":\\\"stdio\\\""));

      requests.println(toolCall(3, "hop_validate", "{\"path\":\"valid.hpl\"}"));
      String firstValidation = readResponse(reader, responses);
      assertSuccessfulToolResponse(firstValidation, 3);
      assertTrue(firstValidation.contains("\\\"valid\\\":true"));

      requests.println(toolCall(4, "hop_validate", "{\"path\":\"valid.hpl\"}"));
      String secondValidation = readResponse(reader, responses);
      assertSuccessfulToolResponse(secondValidation, 4);
      assertTrue(secondValidation.contains("\\\"valid\\\":true"));
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

  private static String readResponse(ExecutorService reader, BufferedReader responses)
      throws Exception {
    Future<String> response = reader.submit(responses::readLine);
    String line = response.get(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertNotNull(line, "MCP server closed STDIO before returning a response");
    return line;
  }

  private static void assertResponseId(String response, int id) {
    assertTrue(response.contains("\"id\":" + id), response);
  }

  private static void assertSuccessfulToolResponse(String response, int id) {
    assertResponseId(response, id);
    assertTrue(response.contains("\"isError\":false"), response);
  }
}
