package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HopRemoteMcpConnectionTest {
  @TempDir Path root;

  @BeforeAll
  static void initializeHop() throws Exception {
    HopEnvironment.init();
  }

  @Test
  void urlOnlyConnectionRequiresAnActiveSessionAndRejectsCrossOriginAndGet() throws Exception {
    try (var fixture = new Fixture(root, false)) {
      String initialize =
          request(
              "initialize",
              Map.of(
                  "protocolVersion",
                  "2025-11-25",
                  "capabilities",
                  Map.of(),
                  "clientInfo",
                  Map.of("name", "remote-test", "version", "1")));
      assertEquals(409, fixture.send(initialize, null, null).statusCode());
      assertEquals(200, fixture.send(initialize, fixture.session, null).statusCode());
      assertEquals(
          "url-only-client",
          fixture.connection.activity().agent(),
          "Direct MCP clients are identified from standard initialize.clientInfo");
      assertEquals(404, fixture.send(initialize, "unknown-session", null).statusCode());
      for (String origin : List.of("https://untrusted.example", "null", "https:invalid")) {
        assertEquals(403, fixture.send(initialize, fixture.session, origin).statusCode());
      }
      var get = HttpRequest.newBuilder(fixture.uri).timeout(Duration.ofSeconds(5)).GET().build();
      assertEquals(
          405, fixture.client.send(get, HttpResponse.BodyHandlers.ofString()).statusCode());
      fixture.connection.close();
      assertEquals(404, fixture.send(initialize, fixture.session, null).statusCode());
      assertEquals(503, fixture.send(initialize, null, null).statusCode());
    }
  }

  @Test
  void readOnlyHttpAdvertisesTheExistingToolsWithoutExecutionOrMutation() throws Exception {
    try (var fixture = new Fixture(root, false)) {
      var listing = fixture.rpc("tools/list", Map.of());
      String names = JsonUtil.toJson(listing);
      assertTrue(names.contains("hop_catalog"));
      assertFalse(names.contains("hop_prepare_correction_plan"));
      assertFalse(names.contains("hop_execute"));
      assertFalse(names.contains("hop_web_request"));
      var config = fixture.call("hop_config", Map.of());
      assertEquals("streamable_http", config.get("transport"));
      assertEquals(true, config.get("read_only"));
      assertEquals(root.toRealPath().toString(), config.get("project_root"));
      var denied = fixture.rpc("tools/call", Map.of("name", "hop_execute", "arguments", Map.of()));
      assertTrue(denied.containsKey("error"));
    }
  }

  @Test
  void authorsNativeFilesAndRollsBackEntirelyThroughHttp() throws Exception {
    try (var fixture = new Fixture(root, true)) {
      var plan =
          fixture.call(
              "hop_prepare_correction_plan",
              Map.of(
                  "path",
                  "remote.hpl",
                  "presentation",
                  "guided",
                  "operations",
                  List.of(
                      Map.of(
                          "operation",
                          "add_component",
                          "name",
                          "Entrada",
                          "plugin_id",
                          "Dummy",
                          "x",
                          80,
                          "y",
                          80),
                      Map.of(
                          "operation",
                          "add_component",
                          "name",
                          "Salida",
                          "plugin_id",
                          "Dummy",
                          "x",
                          280,
                          "y",
                          80),
                      Map.of("operation", "add_hop", "from", "Entrada", "to", "Salida"),
                      Map.of("operation", "auto_layout"))));
      assertFalse(Files.exists(root.resolve("remote.hpl")));
      assertEquals(true, map(plan.get("presentation")).get("ui_notified"));
      var applied =
          fixture.call(
              "hop_apply_correction_plan",
              Map.of(
                  "plan_id",
                  plan.get("plan_id"),
                  "plan_sha256",
                  plan.get("plan_sha256"),
                  "presentation",
                  "instant"));
      assertTrue(Files.readString(root.resolve("remote.hpl")).contains("Entrada"));
      var mutation = map(applied.get("mutation"));
      var rollback =
          fixture.call(
              "hop_rollback_mutation",
              Map.of(
                  "transaction_id",
                  mutation.get("transaction_id"),
                  "expected_sha256",
                  ProjectFiles.sha256(Files.readAllBytes(root.resolve("remote.hpl")))));
      assertEquals(true, rollback.get("rolled_back"));
      assertFalse(Files.exists(root.resolve("remote.hpl")));
      var config = fixture.call("hop_config", Map.of());
      assertEquals(true, config.get("allow_mutation"));
      assertEquals(false, config.get("allow_execution"));
    }
  }

  @Test
  void httpKeepsProjectConfinementAndDoesNotRouteUnknownSessions() throws Exception {
    Path second = Files.createDirectory(root.resolve("second"));
    try (var first = new Fixture(root, true);
        var other = new Fixture(second, true)) {
      assertEquals(
          404, other.send(request("tools/list", Map.of()), first.session, null).statusCode());
      var rejected =
          first.rpc(
              "tools/call",
              Map.of("name", "hop_read_text", "arguments", Map.of("path", "../outside.hpl")));
      assertEquals(true, map(rejected.get("result")).get("isError"));
      assertFalse(Files.exists(root.getParent().resolve("outside.hpl")));
    }
  }

  @Test
  void multipleActiveSessionsRequireExplicitSelectionAndKeepExistingCallsBound() throws Exception {
    Path otherRoot = Files.createDirectory(root.resolve("other-project"));
    try (var fixture = new Fixture(root, false)) {
      var service =
          new HopMcpService(
              new ProjectFiles(otherRoot), new Variables(), new MemoryMetadataProvider(), false);
      try (var other = HopRemoteMcpConnection.open(service, fixture.uri.resolve("/mcp"))) {
        fixture.registry.add(other);
        String configRequest =
            request("tools/call", Map.of("name", "hop_config", "arguments", Map.of()));
        assertEquals(409, fixture.send(configRequest, null, null).statusCode());
        var selected =
            fixture.client.send(
                HttpRequest.newBuilder(URI.create(other.sessionEndpoint()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            request(
                                "initialize",
                                Map.of(
                                    "protocolVersion",
                                    "2025-11-25",
                                    "capabilities",
                                    Map.of(),
                                    "clientInfo",
                                    Map.of("name", "selected", "version", "1")))))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, selected.statusCode());
        String binding = selected.headers().firstValue("mcp-session-id").orElseThrow();
        var response = fixture.send(configRequest, binding, null);
        assertEquals(200, response.statusCode());
        var rpc = map(new Gson().fromJson(response.body(), Map.class));
        var config = map(map(rpc.get("result")).get("structuredContent"));
        assertEquals(otherRoot.toRealPath().toString(), config.get("project_root"));
        assertEquals(
            root.toRealPath().toString(), fixture.call("hop_config", Map.of()).get("project_root"));
        fixture.connection.close();
        assertEquals(404, fixture.send(configRequest, fixture.session, null).statusCode());
      }
    }
  }

  @Test
  void rejectsOversizedChunkedBodiesAndRecoversAfterMalformedJson() throws Exception {
    try (var fixture = new Fixture(root, false)) {
      assertEquals(400, fixture.send("{", fixture.session, null).statusCode());
      byte[] large =
          ("{\"padding\":\"" + "x".repeat(HopRemoteMcpConnection.MAX_REQUEST_BYTES) + "\"}")
              .getBytes(StandardCharsets.UTF_8);
      var oversized =
          fixture
              .builder()
              .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(large)))
              .build();
      assertEquals(
          413, fixture.client.send(oversized, HttpResponse.BodyHandlers.ofString()).statusCode());
      assertEquals(true, fixture.call("hop_config", Map.of()).get("read_only"));
    }
  }

  @Test
  void boundsConcurrentRequestsAndRevokesWhileAcceptedRequestsFinish() throws Exception {
    try (var fixture = new Fixture(root, false);
        var first = new Socket(fixture.uri.getHost(), fixture.uri.getPort());
        var second = new Socket(fixture.uri.getHost(), fixture.uri.getPort())) {
      String body = request("tools/call", Map.of("name", "hop_config", "arguments", Map.of()));
      for (var socket : List.of(first, second)) {
        socket.setSoTimeout(5000);
        String headers =
            "POST /mcp HTTP/1.1\r\nHost: "
                + fixture.uri.getAuthority()
                + "\r\nMcp-Session-Id: "
                + fixture.session
                + "\r\nContent-Type: application/json\r\nAccept: application/json, text/event-stream"
                + "\r\nMCP-Protocol-Version: 2025-11-25\r\nConnection: close\r\nContent-Length: "
                + body.getBytes(StandardCharsets.UTF_8).length
                + "\r\n\r\n";
        socket
            .getOutputStream()
            .write((headers + body.substring(0, 1)).getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
      }
      long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
      int status;
      do {
        status = fixture.send(body, fixture.session, null).statusCode();
      } while (status == 200 && System.nanoTime() < deadline);
      assertEquals(429, status);
      fixture.connection.close();
      assertEquals(404, fixture.send(body, fixture.session, null).statusCode());
      for (var socket : List.of(first, second)) {
        socket.getOutputStream().write(body.substring(1).getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
        String statusLine =
            new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                .readLine();
        assertTrue(statusLine.startsWith("HTTP/1.1 200"), statusLine);
      }
    }
  }

  @Test
  void activityIgnoresRejectedRequestsAndSettlesAfterShortAcceptedRequests() throws Exception {
    try (var fixture = new Fixture(root, false)) {
      assertTrue(fixture.connection.activity().connected());
      assertFalse(
          fixture.connection.isWorking(), "Initialization is presence, not a tool operation");
      awaitIdle(fixture.connection);
      var get = HttpRequest.newBuilder(fixture.uri).GET().build();
      assertEquals(
          405, fixture.client.send(get, HttpResponse.BodyHandlers.ofString()).statusCode());
      assertEquals(
          403,
          fixture
              .send(request("tools/list", Map.of()), fixture.session, "https://untrusted.example")
              .statusCode());
      assertFalse(fixture.connection.isWorking());
      fixture.call("hop_config", Map.of());
      assertTrue(
          fixture.connection.isWorking(), "Short calls remain visible for one breathing cycle");
      awaitIdle(fixture.connection);
      fixture.call("hop_config", Map.of());
      fixture.connection.close();
      assertFalse(
          fixture.connection.isWorking(), "Disabling Live Sync immediately clears activity");
    }
  }

  private static void awaitIdle(HopRemoteMcpConnection connection) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
    while (connection.isWorking() && System.nanoTime() < deadline) Thread.sleep(25);
    assertFalse(connection.isWorking(), "Idle sessions must not pulse forever");
  }

  @Test
  void malformedAndOversizedInitialRequestsDoNotLeaveAnAgentConnected() throws Exception {
    try (var fixture = new Fixture(root, true, false)) {
      String tooLarge =
          "{\"padding\":\"" + "x".repeat(HopRemoteMcpConnection.MAX_REQUEST_BYTES) + "\"}";
      assertEquals(413, fixture.send(tooLarge, null, null).statusCode());
      assertFalse(fixture.connection.activity().connected());
      assertEquals(400, fixture.send("{", null, null).statusCode());
      assertFalse(fixture.connection.activity().connected());
    }
  }

  @Test
  void aQueuedRequestCannotExecuteUnderTheAgentThatReconnectsAfterRevocation() throws Exception {
    try (var fixture = new Fixture(root, true);
        var next = new HopWebAgentClient(null);
        var socket = new Socket("127.0.0.1", fixture.uri.getPort())) {
      socket.setSoTimeout(5000);
      String body =
          request(
              "tools/call",
              Map.of(
                  "name",
                  "hop_prepare_correction_plan",
                  "arguments",
                  Map.of("path", "revoked.hpl", "operations", List.of())));
      String headers =
          "POST "
              + fixture.uri.getRawPath()
              + "?servicehandler=hop-mcp HTTP/1.1\r\n"
              + "Host: 127.0.0.1:"
              + fixture.uri.getPort()
              + "\r\nMcp-Session-Id: "
              + fixture.session
              + "\r\nMCP-Protocol-Version: 2025-11-25\r\nAccept: application/json, text/event-stream"
              + "\r\nContent-Type: application/json\r\nContent-Length: "
              + body.getBytes(StandardCharsets.UTF_8).length
              + "\r\nConnection: close\r\n\r\n";
      socket
          .getOutputStream()
          .write((headers + body.substring(0, 1)).getBytes(StandardCharsets.UTF_8));
      socket.getOutputStream().flush();
      fixture.connection.disconnectAgent();
      next.agentInfo(new io.modelcontextprotocol.spec.McpSchema.Implementation("New agent", "1"));
      assertEquals(true, next.connect(fixture.uri.resolve("/mcp").toString()).get("connected"));
      socket.getOutputStream().write(body.substring(1).getBytes(StandardCharsets.UTF_8));
      socket.getOutputStream().flush();
      String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      boolean rejectedBeforeDispatch =
          response.startsWith("HTTP/1.1 404") && response.contains("AGENT_DISCONNECTED");
      boolean rejectedAtToolDispatch =
          response.startsWith("HTTP/1.1 200") && response.contains("\"isError\":true");
      assertTrue(rejectedBeforeDispatch || rejectedAtToolDispatch, response);
      assertFalse(response.contains("\"state\":\"prepared\""));
      assertEquals("New agent", fixture.connection.activity().agent());
      assertEquals(true, next.status().get("connected"));
      assertFalse(Files.exists(root.resolve("revoked.hpl")));
    }
  }

  @Test
  void requiresHttpsExceptForLocalTestsAndRejectsAmbiguousPublicUrls() {
    assertDoesNotThrow(
        () -> HopRemoteMcpConnection.requirePublicUi(URI.create("https://hop.example/hop_web/ui")));
    for (String invalid :
        List.of(
            "http://hop.example/ui",
            "https://user:password@hop.example/ui",
            "https://hop.example/ui?project=other",
            "https://hop.example/ui#fragment",
            "https://hop.example/ui;jsessionid=private",
            "https://hop.example/")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> HopRemoteMcpConnection.requirePublicUi(URI.create(invalid)));
    }
  }

  private static String request(String method, Map<String, Object> params) {
    return JsonUtil.toJson(Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  static final class Fixture implements AutoCloseable {
    final HttpClient client = HttpClient.newHttpClient();
    final Tomcat tomcat;
    final HopRemoteMcpConnection connection;
    final HopLiveUiEventBroker.LiveSession ui;
    final URI uri;
    final String session;
    final HopWebRemoteEndpoint registry = new HopWebRemoteEndpoint();

    Fixture(Path root, boolean allowMutation) throws Exception {
      this(root, allowMutation, true);
    }

    Fixture(Path root, boolean allowMutation, boolean initialize) throws Exception {
      var servlet =
          new HttpServlet() {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException {
              registry.service(request, response);
            }
          };
      tomcat = HopMcpConformanceServer.createTomcat(servlet, 0);
      // RAP's servlet is synchronous. The MCP transport must work without Servlet async support.
      for (var child : tomcat.getHost().findChildren()) {
        for (var wrapper : child.findChildren()) ((Wrapper) wrapper).setAsyncSupported(false);
      }
      tomcat.start();
      var broker = new HopLiveUiEventBroker(root);
      ui = broker.openSession("web");
      var service =
          new HopMcpService(
              new ProjectFiles(root),
              new Variables(),
              new MemoryMetadataProvider(),
              false,
              false,
              allowMutation,
              false,
              null,
              broker);
      var opened =
          HopRemoteMcpConnection.open(
              service,
              URI.create("http://127.0.0.1:" + tomcat.getConnector().getLocalPort() + "/mcp"));
      connection = opened;
      uri = URI.create(connection.endpoint());
      registry.add(connection);
      if (!initialize) {
        session = null;
        return;
      }
      var initialized =
          send(
              request(
                  "initialize",
                  Map.of(
                      "protocolVersion",
                      "2025-11-25",
                      "capabilities",
                      Map.of(),
                      "clientInfo",
                      Map.of("name", "url-only-client", "version", "1"))),
              null,
              null);
      assertEquals(200, initialized.statusCode());
      session = initialized.headers().firstValue("mcp-session-id").orElseThrow();
    }

    HttpRequest.Builder builder() {
      return HttpRequest.newBuilder(uri)
          .timeout(Duration.ofSeconds(10))
          .header("Mcp-Session-Id", session)
          .header("Content-Type", "application/json")
          .header("Accept", "application/json, text/event-stream")
          .header("MCP-Protocol-Version", "2025-11-25");
    }

    HttpResponse<String> send(String body, String suppliedSession, String origin) throws Exception {
      var request =
          HttpRequest.newBuilder(uri)
              .timeout(Duration.ofSeconds(10))
              .header("Content-Type", "application/json")
              .header("Accept", "application/json, text/event-stream")
              .header("MCP-Protocol-Version", "2025-11-25");
      if (suppliedSession != null) request.header("Mcp-Session-Id", suppliedSession);
      if (origin != null) request.header("Origin", origin);
      return client.send(
          request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
          HttpResponse.BodyHandlers.ofString());
    }

    Map<String, Object> rpc(String method, Map<String, Object> params) throws Exception {
      var response = send(request(method, params), session, null);
      assertEquals(200, response.statusCode(), response.body());
      return map(new Gson().fromJson(response.body(), Map.class));
    }

    Map<String, Object> call(String name, Map<String, Object> args) throws Exception {
      var response = rpc("tools/call", Map.of("name", name, "arguments", args));
      assertFalse(response.containsKey("error"), () -> JsonUtil.toJson(response));
      var result = map(response.get("result"));
      assertNotEquals(true, result.get("isError"), () -> JsonUtil.toJson(result));
      return map(result.get("structuredContent"));
    }

    @Override
    public void close() throws Exception {
      connection.close();
      ui.close();
      client.close();
      tomcat.stop();
      tomcat.destroy();
      connection.awaitDisposal(Duration.ofSeconds(5));
    }
  }
}
