package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.hop.core.HopEnvironment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HopWebAgentClientTest {
  @TempDir Path root;

  @BeforeAll
  static void initializeHop() throws Exception {
    HopEnvironment.init();
  }

  @Test
  void offersProjectAndOpenFileChoicesAndDoesNotSelectAnEditorUntilTheUserChooses()
      throws Exception {
    try (var fixture = new HopRemoteMcpConnectionTest.Fixture(root, true, false);
        var client = new HopWebAgentClient(null)) {
      var service =
          new HopMcpService(
              new ProjectFiles(root),
              new org.apache.hop.core.variables.Variables(),
              new org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider(),
              false,
              false,
              true,
              false,
              null,
              new HopLiveUiEventBroker(root));
      try (var other = HopRemoteMcpConnection.open(service, fixture.uri.resolve("/mcp"))) {
        fixture.registry.add(other);
        fixture.connection.editor("Proyecto A", "primero.hpl");
        other.editor("Proyecto B", "segundo.hwf");
        var choices = client.connect(fixture.uri.resolve("/mcp").toString());
        assertEquals("ambiguous", choices.get("state"));
        var sessions = (java.util.List<?>) choices.get("sessions");
        assertEquals(2, sessions.size());
        assertTrue(JsonUtil.toJson(sessions).contains("primero.hpl"));
        assertTrue(JsonUtil.toJson(sessions).contains("segundo.hwf"));
        assertFalse(fixture.connection.activity().connected());
        assertFalse(other.activity().connected());
        client.agentInfo(new io.modelcontextprotocol.spec.McpSchema.Implementation("Codex", "1"));
        var chosen = client.connect(fixture.uri.resolve("/mcp").toString(), other.id());
        assertEquals(true, chosen.get("connected"), () -> JsonUtil.toJson(chosen));
        assertTrue(other.activity().connected());
        assertEquals("Codex", other.activity().agent());
        assertFalse(fixture.connection.activity().connected());
        assertEquals(true, client.status().get("connected"));
      }
    }
  }

  @Test
  void iconDisconnectRevokesTheOldBindingButTheSameEditorCanReconnect() throws Exception {
    try (var fixture = new HopRemoteMcpConnectionTest.Fixture(root, true, false);
        var client = new HopWebAgentClient(null)) {
      String url = fixture.uri.resolve("/mcp").toString();
      assertEquals(true, client.connect(url).get("connected"));
      String editor = (String) client.status().get("session_id");
      client.call("hop_config", Map.of());
      long activity = fixture.connection.activity().lastActivityMillis();
      Thread.sleep(2500);
      client.heartbeat();
      assertFalse(fixture.connection.activity().working());
      assertEquals(activity, fixture.connection.activity().lastActivityMillis());
      fixture.connection.disconnectAgent();
      assertFalse(fixture.connection.isClosed());
      assertEquals("disconnected", client.status().get("state"));
      assertThrows(IllegalStateException.class, () -> client.call("hop_catalog", Map.of()));
      assertEquals(true, client.connect(url, editor).get("connected"));
      assertTrue(fixture.connection.activity().connected());
    }
  }

  @Test
  void anotherAgentCannotTakeOverAndClosingTheAgentLeavesTheEditorAvailable() throws Exception {
    try (var fixture = new HopRemoteMcpConnectionTest.Fixture(root, true, false);
        var first = new HopWebAgentClient(null);
        var second = new HopWebAgentClient(null)) {
      String url = fixture.uri.resolve("/mcp").toString();
      assertEquals(true, first.connect(url).get("connected"));
      assertEquals("busy", second.connect(url).get("state"));
      assertEquals(true, first.status().get("connected"));
      first.close();
      assertFalse(fixture.connection.activity().connected());
      assertFalse(fixture.connection.isClosed());
      assertEquals(true, second.connect(url).get("connected"));
    }
  }

  @Test
  void userSuppliesOnlyTheWebUrlAndDiscoversNativeToolsWithoutLocalProjectSetup() throws Exception {
    try (var fixture = new HopRemoteMcpConnectionTest.Fixture(root, true, false);
        var client = new HopWebAgentClient(null)) {
      var connected = client.connect(fixture.uri.resolve("/mcp").toString());
      assertEquals(true, connected.get("connected"));
      assertEquals(root.toRealPath().toString(), connected.get("project_root"));
      assertEquals(true, connected.get("allow_mutation"));
      var selected = client.tools("hop_prepare_correction_plan", 0, 1);
      assertEquals(1, selected.get("count"));
      var prepared =
          client.call(
              "hop_prepare_correction_plan",
              Map.of(
                  "path",
                  "agent.hpl",
                  "presentation",
                  "guided",
                  "operations",
                  java.util.List.of()));
      assertEquals("prepared", ((Map<?, ?>) prepared.structuredContent()).get("state"));
      assertTrue(java.nio.file.Files.notExists(root.resolve("agent.hpl")));
      assertThrows(IllegalArgumentException.class, () -> client.call("hop_execute", Map.of()));
      assertEquals(true, client.status().get("connected"));
    }
  }

  @Test
  void inactiveLiveSyncIsReportedWithoutFallingBackToLocalFiles() throws Exception {
    try (var fixture = new HopRemoteMcpConnectionTest.Fixture(root, true, false);
        var client = new HopWebAgentClient(null)) {
      assertEquals(true, client.connect(fixture.uri.toString()).get("connected"));
      fixture.connection.close();
      assertEquals("inactive", client.status().get("state"));
      assertThrows(IllegalStateException.class, () -> client.call("hop_catalog", Map.of()));
      var inactive = client.connect(fixture.uri.toString());
      assertEquals(false, inactive.get("connected"));
      assertEquals("inactive", inactive.get("state"));
      assertTrue(String.valueOf(inactive.get("message")).contains("no está activo"));
    }
  }

  @Test
  void remoteNativeValidationErrorsDoNotCorruptTheConnection() throws Exception {
    try (var fixture = new HopRemoteMcpConnectionTest.Fixture(root, true, false);
        var client = new HopWebAgentClient(null)) {
      client.connect(fixture.uri.toString());
      var rejected = client.call("hop_read_text", Map.of("path", "../outside.hpl"));
      assertTrue(rejected.isError());
      assertEquals(true, client.status().get("connected"));
      var config = client.call("hop_config", Map.of());
      assertEquals("streamable_http", ((Map<?, ?>) config.structuredContent()).get("transport"));
    }
  }

  @Test
  void boundsResponseBytesAndDoesNotFollowRedirects() throws Exception {
    var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var followed = new java.util.concurrent.atomic.AtomicInteger();
    http.createContext(
        "/large",
        exchange -> {
          byte[] body = new byte[512 * 1024 + 1];
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (var output = exchange.getResponseBody()) {
            output.write(body);
          }
        });
    http.createContext(
        "/redirect",
        exchange -> {
          exchange.getResponseHeaders().set("Location", "/unexpected");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    http.createContext(
        "/unexpected",
        exchange -> {
          followed.incrementAndGet();
          exchange.sendResponseHeaders(500, -1);
          exchange.close();
        });
    http.start();
    try (var client = new HopWebAgentClient(null)) {
      String base = "http://127.0.0.1:" + http.getAddress().getPort();
      assertEquals(false, client.connect(base + "/large").get("connected"));
      assertEquals(false, client.connect(base + "/redirect").get("connected"));
      assertEquals(0, followed.get());
    } finally {
      http.stop(0);
    }
  }

  @Test
  void deadlineIncludesSlowResponseBodyReception() throws Exception {
    var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var release = new CountDownLatch(1);
    http.createContext(
        "/slow",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, 0);
          try (var output = exchange.getResponseBody()) {
            output.write('{');
            output.flush();
            try {
              release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
          }
        });
    http.start();
    try (var client = new HopWebAgentClient(null, Duration.ofMillis(200))) {
      assertTimeoutPreemptively(
          Duration.ofSeconds(2),
          () -> {
            var result =
                client.connect("http://127.0.0.1:" + http.getAddress().getPort() + "/slow");
            assertEquals(false, result.get("connected"));
          });
    } finally {
      release.countDown();
      http.stop(0);
    }
  }

  @Test
  void acceptsNormalUiUrlsAndRejectsCredentialsOrBrowserSessionParameters() {
    assertEquals(
        URI.create("https://hop.example/hop_web/ui?servicehandler=hop-mcp"),
        HopWebAgentClient.endpoint("https://hop.example/hop_web/ui"));
    for (String url :
        java.util.List.of(
            "https://user:password@hop.example/ui",
            "https://hop.example/ui;jsessionid=private",
            "http://hop.example/ui")) {
      assertThrows(IllegalArgumentException.class, () -> HopWebAgentClient.endpoint(url));
    }
  }
}
