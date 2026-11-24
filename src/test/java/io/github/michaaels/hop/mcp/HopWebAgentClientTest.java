package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
  void acceptsOnlyTheCopiedLinkAndReportsOnlyTheConnectedEditorsOpenFile() throws Exception {
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
        fixture.connection.editorFile("primero.hpl");
        other.editorFile("segundo.hwf");
        var choices = client.connect(fixture.uri.resolve("/mcp").toString());
        assertEquals("pairing_required", choices.get("state"));
        assertFalse(choices.containsKey("sessions"));
        assertFalse(fixture.connection.activity().connected());
        assertFalse(other.activity().connected());
        client.agentInfo(new io.modelcontextprotocol.spec.McpSchema.Implementation("Codex", "1"));
        var chosen = client.connect(other.createPairingLink());
        assertEquals(true, chosen.get("connected"), () -> JsonUtil.toJson(chosen));
        assertTrue(other.activity().connected());
        assertEquals("Codex", other.activity().agent());
        assertFalse(fixture.connection.activity().connected());
        assertEquals(true, client.status().get("connected"));
        var live = (Map<?, ?>) client.call("hop_live_ui_status", Map.of()).structuredContent();
        assertEquals("segundo.hwf", live.get("active_file"));
        assertFalse(JsonUtil.toJson(live).contains("primero.hpl"));
      }
    }
  }

  @Test
  void browserLinksBindTwoEditorsAndOnlyTheOwnerCanApplyAReservedDefinition() throws Exception {
    String path = "shared.hwf";
    try (var fixture = new HopRemoteMcpConnectionTest.Fixture(root, true, false);
        var first = new HopWebAgentClient(null);
        var second = new HopWebAgentClient(null)) {
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
      service.webPresentation("result_only", "TEST_PROJECT");
      try (var other = HopRemoteMcpConnection.open(service, fixture.uri.resolve("/mcp"))) {
        fixture.registry.add(other);
        assertEquals(true, first.connect(fixture.connection.createPairingLink()).get("connected"));
        var connected = second.connect(other.createPairingLink());
        assertEquals(other.id(), connected.get("session_id"));
        assertEquals(
            fixture.uri.resolve("/mcp") + "?session=" + other.id(), connected.get("ui_url"));
        assertEquals("result_only", connected.get("presentation_default"));
        assertEquals("TEST_PROJECT", connected.get("project_name"));
        var before = second.call("hop_live_ui_status", Map.of());
        assertFalse(before.isError(), before.toString());
        assertEquals(false, ((Map<?, ?>) before.structuredContent()).get("other_edit_in_progress"));
        assertFalse(((Map<?, ?>) before.structuredContent()).containsKey("active_sessions"));

        var prepared =
            first.call(
                "hop_prepare_correction_plan", Map.of("path", path, "operations", List.of()));
        assertFalse(prepared.isError(), prepared.toString());
        var during = second.call("hop_live_ui_status", Map.of());
        assertFalse(during.isError(), during.toString());
        assertEquals(true, ((Map<?, ?>) during.structuredContent()).get("other_edit_in_progress"));
        assertFalse(JsonUtil.toJson(during.structuredContent()).contains(path));
        var rejected =
            second.call(
                "hop_prepare_correction_plan", Map.of("path", path, "operations", List.of()));
        assertTrue(rejected.isError(), rejected.toString());
        assertTrue(JsonUtil.toJson(rejected.structuredContent()).contains("DEFINITION_IN_USE"));
        String conflict = JsonUtil.toJson(rejected.structuredContent());
        assertTrue(conflict.contains(path));
        assertFalse(conflict.contains(fixture.connection.id().substring(0, 8)));
        assertEquals(new HopWebProjectWork.ConflictNotice(path), other.takeConflict());
        assertNull(fixture.connection.takeConflict());
        assertTrue(
            second
                .call("hop_prepare_correction_plan", Map.of("path", path, "operations", List.of()))
                .isError());
        assertNull(other.takeConflict(), "Retries do not reopen the same conflict alert");
        var otherPath =
            second.call(
                "hop_prepare_correction_plan",
                Map.of("path", "other.hwf", "operations", List.of()));
        assertFalse(otherPath.isError(), otherPath.toString());

        fixture.connection.disconnectAgent();
        var replacement =
            second.call(
                "hop_prepare_correction_plan", Map.of("path", path, "operations", List.of()));
        assertFalse(replacement.isError(), replacement.toString());
        assertEquals(true, first.connect(fixture.connection.createPairingLink()).get("connected"));
        var stale =
            first.call(
                "hop_apply_correction_plan",
                Map.of(
                    "plan_id",
                    ((Map<?, ?>) prepared.structuredContent()).get("plan_id"),
                    "plan_sha256",
                    ((Map<?, ?>) prepared.structuredContent()).get("plan_sha256")));
        assertTrue(stale.isError(), stale.toString());
        assertTrue(JsonUtil.toJson(stale.structuredContent()).contains("STALE_AGENT_PLAN"));
      }
    }
  }

  @Test
  void iconDisconnectRevokesTheOldBindingButTheSameEditorCanReconnect() throws Exception {
    try (var fixture = new HopRemoteMcpConnectionTest.Fixture(root, true, false);
        var client = new HopWebAgentClient(null)) {
      String url = fixture.connection.createPairingLink();
      assertEquals(true, client.connect(url).get("connected"));
      String editor = (String) client.status().get("session_id");
      client.call("hop_config", Map.of());
      long activity = fixture.connection.activity().lastActivityMillis();
      Thread.sleep(2500);
      client.heartbeat();
      assertFalse(fixture.connection.activity().working());
      assertEquals(activity, fixture.connection.activity().lastActivityMillis());
      fixture.connection.disconnectAgent();
      assertEquals("", fixture.connection.id());
      assertFalse(fixture.connection.isClosed());
      assertEquals("disconnected", client.status().get("state"));
      assertThrows(IllegalStateException.class, () -> client.call("hop_catalog", Map.of()));
      assertEquals("pairing_required", client.connect(url).get("state"));
      var replacement = client.connect(fixture.connection.createPairingLink());
      assertEquals(true, replacement.get("connected"));
      assertNotEquals(editor, replacement.get("session_id"));
      assertTrue(fixture.connection.activity().connected());
    }
  }

  @Test
  void anotherAgentCannotTakeOverAndClosingTheAgentLeavesTheEditorAvailable() throws Exception {
    try (var fixture = new HopRemoteMcpConnectionTest.Fixture(root, true, false);
        var first = new HopWebAgentClient(null);
        var second = new HopWebAgentClient(null)) {
      String url = fixture.connection.createPairingLink();
      assertEquals(true, first.connect(url).get("connected"));
      assertEquals("pairing_required", second.connect(url).get("state"));
      assertEquals(
          "pairing_required", second.connect((String) first.status().get("ui_url")).get("state"));
      assertEquals(true, first.status().get("connected"));
      first.close();
      assertFalse(fixture.connection.activity().connected());
      assertFalse(fixture.connection.isClosed());
      assertEquals(true, second.connect(fixture.connection.createPairingLink()).get("connected"));
    }
  }

  @Test
  void discoversNativeToolsWithoutLocalProjectSetup() throws Exception {
    try (var fixture = new HopRemoteMcpConnectionTest.Fixture(root, true, false);
        var client = new HopWebAgentClient(null)) {
      var connected = client.connect(fixture.connection.createPairingLink());
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
  void stoppedEditorIsReportedWithoutFallingBackToLocalFiles() throws Exception {
    try (var fixture = new HopRemoteMcpConnectionTest.Fixture(root, true, false);
        var client = new HopWebAgentClient(null)) {
      assertEquals(true, client.connect(fixture.connection.createPairingLink()).get("connected"));
      fixture.connection.close();
      assertEquals("disconnected", client.status().get("state"));
      assertThrows(IllegalStateException.class, () -> client.call("hop_catalog", Map.of()));
      var inactive = client.connect(fixture.uri.toString());
      assertEquals(false, inactive.get("connected"));
      assertEquals("pairing_required", inactive.get("state"));
    }
  }

  @Test
  void remoteNativeValidationErrorsDoNotCorruptTheConnection() throws Exception {
    try (var fixture = new HopRemoteMcpConnectionTest.Fixture(root, true, false);
        var client = new HopWebAgentClient(null)) {
      client.connect(fixture.connection.createPairingLink());
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
      assertEquals(false, client.connect(base + "/large" + TEST_PAIRING).get("connected"));
      assertEquals(false, client.connect(base + "/redirect" + TEST_PAIRING).get("connected"));
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
                client.connect(
                    "http://127.0.0.1:" + http.getAddress().getPort() + "/slow" + TEST_PAIRING);
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

  private static final String TEST_PAIRING = "#pair=" + "a".repeat(43);
}
