package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.hop.core.HopEnvironment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HopWebAgentServerTest {
  @TempDir Path root;

  @BeforeAll
  static void initializeHop() throws Exception {
    HopEnvironment.init();
  }

  @Test
  void stdioPublishesOnlyFourSmallRemoteToolsAndValidatesInputs() throws Exception {
    try (var session = new Session()) {
      var listing = session.rpc("tools/list", Map.of());
      var tools = (List<?>) map(listing.get("result")).get("tools");
      assertEquals(4, tools.size());
      assertTrue(JsonUtil.toJson(listing).getBytes(StandardCharsets.UTF_8).length < 12000);
      var invalid =
          session.rpc(
              "tools/call", Map.of("name", "hop_web_connect", "arguments", Map.of("url", 42)));
      if (invalid.containsKey("error")) {
        assertEquals(-32602, ((Number) map(invalid.get("error")).get("code")).intValue());
      } else {
        assertEquals(true, map(invalid.get("result")).get("isError"));
      }
      var status = session.call("hop_web_status", Map.of());
      assertEquals(false, map(map(status.get("result")).get("structuredContent")).get("connected"));
    }
  }

  @Test
  void stdioConnectsByNormalUrlAndReportsWhenLiveSyncStops() throws Exception {
    try (var fixture = new HopRemoteMcpConnectionTest.Fixture(root, true, false);
        var session = new Session()) {
      var connected =
          session.call("hop_web_connect", Map.of("url", fixture.uri.resolve("/mcp").toString()));
      assertEquals(
          true, map(map(connected.get("result")).get("structuredContent")).get("connected"));
      assertNotEquals(
          "No informado",
          fixture.connection.activity().agent(),
          "The proxy forwards the actual MCP client's reported identity");
      var nativeConfig =
          session.call("hop_web_call", Map.of("name", "hop_config", "arguments", Map.of()));
      assertEquals(
          "streamable_http",
          map(map(nativeConfig.get("result")).get("structuredContent")).get("transport"));
      fixture.connection.close();
      var stopped = session.call("hop_web_status", Map.of());
      var state = map(map(stopped.get("result")).get("structuredContent"));
      assertEquals(false, state.get("connected"));
      assertEquals("inactive", state.get("state"));
      var rejected =
          session.call("hop_web_call", Map.of("name", "hop_catalog", "arguments", Map.of()));
      assertEquals(true, map(rejected.get("result")).get("isError"));
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  private static final class Session implements AutoCloseable {
    private final Pipe input = Pipe.open();
    private final Pipe output = Pipe.open();
    private final HopWebAgentServer server;
    private final PrintWriter writer;
    private final BufferedReader reader;
    private int sequence;

    Session() throws Exception {
      server =
          new HopWebAgentServer(
              Channels.newInputStream(input.source()),
              Channels.newOutputStream(output.sink()),
              null);
      writer =
          new PrintWriter(
              new OutputStreamWriter(
                  Channels.newOutputStream(input.sink()), StandardCharsets.UTF_8),
              true);
      reader =
          new BufferedReader(
              new InputStreamReader(
                  Channels.newInputStream(output.source()), StandardCharsets.UTF_8));
      rpc(
          "initialize",
          Map.of(
              "protocolVersion",
              "2025-11-25",
              "capabilities",
              Map.of(),
              "clientInfo",
              Map.of("name", "stdio-web-test", "version", "1")));
      writer.println(
          JsonUtil.toJson(Map.of("jsonrpc", "2.0", "method", "notifications/initialized")));
    }

    Map<String, Object> rpc(String method, Map<String, Object> params) throws Exception {
      int id = ++sequence;
      writer.println(
          JsonUtil.toJson(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params)));
      return assertTimeoutPreemptively(
          Duration.ofSeconds(10),
          () -> {
            for (; ; ) {
              String line = reader.readLine();
              assertNotNull(line);
              var response = map(new Gson().fromJson(line, Map.class));
              if (response.get("id") instanceof Number responseId && responseId.intValue() == id)
                return response;
            }
          });
    }

    Map<String, Object> call(String name, Map<String, Object> args) throws Exception {
      return rpc("tools/call", Map.of("name", name, "arguments", args));
    }

    @Override
    public void close() throws Exception {
      writer.close();
      server.close();
      reader.close();
      input.source().close();
      output.sink().close();
    }
  }
}
