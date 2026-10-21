package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HopWebClientTest {
  private HttpServer server;
  private AtomicReference<String> requestPath;
  private AtomicReference<String> authorization;
  private AtomicInteger redirectTargetRequests;
  private AtomicInteger requestsReceived;

  @BeforeEach
  void setUp() throws Exception {
    requestPath = new AtomicReference<>();
    authorization = new AtomicReference<>();
    redirectTargetRequests = new AtomicInteger();
    requestsReceived = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requestsReceived.incrementAndGet();
          requestPath.set(exchange.getRequestURI().toString());
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          String path = exchange.getRequestURI().getPath();
          String query = exchange.getRequestURI().getRawQuery();
          if ("fixture=redirect".equals(query)) {
            exchange.getResponseHeaders().add("Location", "/hop/startPipeline");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
            return;
          }
          if ("/hop/startPipeline".equals(path)) {
            redirectTargetRequests.incrementAndGet();
          }
          if ("fixture=large".equals(query)) {
            exchange.getResponseHeaders().add("X-Large", "h".repeat(4096));
            byte[] largeResponse = new byte[HopWebClient.MAX_RESPONSE_READ_BYTES + 1024];
            Arrays.fill(largeResponse, (byte) 'a');
            exchange.sendResponseHeaders(200, largeResponse.length);
            exchange.getResponseBody().write(largeResponse);
            exchange.close();
            return;
          }
          if ("fixture=unicode".equals(query)) {
            byte[] response =
                ("a".repeat(HopWebClient.MAX_WEB_BODY_RETURN_BYTES - 1) + "éz")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
            return;
          }
          exchange.getResponseHeaders().add("Set-Cookie", "session=secret");
          exchange.getResponseHeaders().add("X-Api-Key", "header-secret");
          exchange.getResponseHeaders().add("X-Credential", "credential-secret");
          exchange.getResponseHeaders().add("X-Auth", "auth-secret");
          byte[] response =
              "{\"ok\":true,\"token\":\"server-secret\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void timeoutIncludesAStalledBodyAndTheClientCanRetry() throws Exception {
    CountDownLatch bodyStarted = new CountDownLatch(1);
    CountDownLatch releaseBody = new CountDownLatch(1);
    CountDownLatch handlerFinished = new CountDownLatch(1);
    server.createContext(
        "/hop/pipelineStatus",
        exchange -> {
          try {
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write('o');
            exchange.getResponseBody().flush();
            bodyStarted.countDown();
            try {
              releaseBody.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
          } finally {
            exchange.close();
            handlerFinished.countDown();
          }
        });
    HopWebClient client = new HopWebClient(baseUrl(), null, null, null, 1);
    try (var callers = Executors.newSingleThreadExecutor()) {
      var request =
          callers.submit(
              () ->
                  assertThrows(
                      HttpTimeoutException.class,
                      () -> client.request("GET", "/pipelineStatus", Map.of())));
      try {
        assertTrue(bodyStarted.await(5, TimeUnit.SECONDS));
        request.get(3, TimeUnit.SECONDS);
      } finally {
        releaseBody.countDown();
      }
    }
    assertTrue(handlerFinished.await(5, TimeUnit.SECONDS));
    assertEquals(200, client.request("GET", "/status", Map.of()).get("status"));
  }

  @Test
  void interruptedBodyReceptionReleasesTheCaller() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    server.createContext(
        "/hop/workflowStatus",
        exchange -> {
          try {
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write('o');
            exchange.getResponseBody().flush();
            started.countDown();
            try {
              release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
          } finally {
            exchange.close();
          }
        });
    HopWebClient client = new HopWebClient(baseUrl(), null, null, null, 5);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread caller =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    client.request("GET", "/workflowStatus", Map.of());
                  } catch (Throwable exception) {
                    failure.set(exception);
                  }
                });
    try {
      assertTrue(started.await(5, TimeUnit.SECONDS));
      caller.interrupt();
      caller.join(3000);
      assertFalse(caller.isAlive());
      assertTrue(failure.get() instanceof InterruptedException);
    } finally {
      release.countDown();
      caller.interrupt();
      caller.join(3000);
    }
  }

  @Test
  void requestUsesConfiguredBasePathAndRedactsSensitiveData() throws Exception {
    HopWebClient client = new HopWebClient(baseUrl(), "user", "pass", null, 5);

    Map<String, Object> result = client.request("GET", "/status?token=caller-secret", Map.of());

    assertEquals("/hop/status?token=caller-secret", requestPath.get());
    assertEquals("Basic dXNlcjpwYXNz", authorization.get());
    assertEquals(200, result.get("status"));
    assertTrue((Boolean) result.get("ok"));
    assertFalse(String.valueOf(result.get("url")).contains("caller-secret"));
    assertFalse(String.valueOf(result.get("body")).contains("server-secret"));
    assertTrue(String.valueOf(result.get("body")).contains("***REDACTED***"));
    assertEquals(
        "{\"ok\":true,\"token\":\"server-secret\"}".getBytes(StandardCharsets.UTF_8).length,
        result.get("body_bytes"));
    assertEquals(
        ((String) result.get("body")).getBytes(StandardCharsets.UTF_8).length,
        result.get("returned_bytes"));
    Map<?, ?> headers = (Map<?, ?>) result.get("headers");
    assertTrue(headers.containsValue("[REDACTED]"));
    assertEquals("[REDACTED]", headers.get("x-api-key"));
    assertEquals("[REDACTED]", headers.get("x-credential"));
    assertEquals("[REDACTED]", headers.get("x-auth"));
  }

  @Test
  void requestPreservesRawQueryExactlyOnce() throws Exception {
    HopWebClient client = new HopWebClient(baseUrl(), null, null, null, 5);
    String raw = "name=A%20B&literal=%26&plus=%2B&unicode=%C3%A9&empty=&repeat=1&repeat=2";
    Map<String, Object> result = client.request("GET", "/status?" + raw, Map.of());
    assertEquals("/hop/status?" + raw, requestPath.get());
    assertFalse(String.valueOf(result.get("url")).contains(raw));
    assertEquals(true, result.get("query_present"));

    client.request("GET", "/status", Map.of());
    assertEquals("/hop/status", requestPath.get());
    Map<String, Object> emptyQuery = client.request("GET", "/status?", Map.of());
    assertEquals("/hop/status", requestPath.get());
    assertEquals(true, emptyQuery.get("query_present"));
  }

  @Test
  void requestRejectsTraversalAbsoluteUrlsAndMutatingMethods() {
    HopWebClient client = new HopWebClient(baseUrl(), null, null, null, 5);

    assertThrows(
        IllegalArgumentException.class, () -> client.request("GET", "../status", Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> client.request("GET", "http://127.0.0.1/status", Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> client.request("GET", "http://user:secret@127.0.0.1/status", Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> client.request("GET", "//user:secret@127.0.0.1/status", Map.of()));
    assertThrows(
        IllegalArgumentException.class, () -> client.request("GET", "/status#fragment", Map.of()));
    assertThrows(
        IllegalArgumentException.class, () -> client.request("GET", "/%2e%2e/status", Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> client.request("GET", "/status/%2e%2e/within-base", Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> client.request("GET", "/%252e%252e/status", Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> client.request("GET", "/status%2f..%2foutside", Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> client.request("GET", "/status%5c..%5coutside", Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> client.request("GET", "/status%252f..%252foutside", Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> client.request("GET", "/status%255c..%255coutside", Map.of()));
    assertThrows(SecurityException.class, () -> client.request("POST", "/status", Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> client.request("GET", "/status", Map.of("X-Credential", "caller-secret")));
    assertThrows(
        IllegalArgumentException.class,
        () -> client.request("GET", "/status", Map.of("X-Auth", "caller-secret")));
    assertThrows(
        IllegalArgumentException.class,
        () -> client.request("GET", "/status", java.util.Collections.singletonMap("X-Test", null)));
  }

  @Test
  void requestRejectsUserInfoInConfiguredBaseUrl() {
    String baseWithUserInfo =
        "http://user:secret@127.0.0.1:" + server.getAddress().getPort() + "/hop";

    assertThrows(
        IllegalArgumentException.class,
        () -> new HopWebClient(baseWithUserInfo, null, null, null, 5));
  }

  @Test
  void requestDoesNotFollowRedirects() throws Exception {
    HopWebClient client = new HopWebClient(baseUrl(), null, null, null, 5);

    Map<String, Object> result = client.request("GET", "/status?fixture=redirect", Map.of());

    assertEquals(302, result.get("status"));
    assertEquals("/hop/status?fixture=redirect", requestPath.get());
    assertEquals(0, redirectTargetRequests.get());
  }

  @Test
  void requestBoundsReturnedBodyAndLargeResponseHeaders() throws Exception {
    HopWebClient client = new HopWebClient(baseUrl(), null, null, null, 5);

    Map<String, Object> result = client.request("GET", "/status?fixture=large", Map.of());

    assertEquals(HopWebClient.MAX_RESPONSE_READ_BYTES, result.get("body_bytes"));
    assertEquals(HopWebClient.MAX_WEB_BODY_RETURN_BYTES, result.get("returned_bytes"));
    assertTrue((Boolean) result.get("body_truncated"));
    assertEquals(
        HopWebClient.MAX_WEB_BODY_RETURN_BYTES,
        ((String) result.get("body")).getBytes(StandardCharsets.UTF_8).length);
    Map<?, ?> headers = (Map<?, ?>) result.get("headers");
    assertEquals("[TRUNCATED]", headers.get("x-large"));
  }

  @Test
  void requestTruncatesBodyAtUtf8CodePointBoundary() throws Exception {
    HopWebClient client = new HopWebClient(baseUrl(), null, null, null, 5);

    Map<String, Object> result = client.request("GET", "/status?fixture=unicode", Map.of());

    assertEquals(HopWebClient.MAX_WEB_BODY_RETURN_BYTES - 1, result.get("returned_bytes"));
    assertEquals("a".repeat(HopWebClient.MAX_WEB_BODY_RETURN_BYTES - 1), result.get("body"));
    assertTrue((Boolean) result.get("body_truncated"));
  }

  @Test
  void requestAllowsNullHeaders() throws Exception {
    HopWebClient client = new HopWebClient(baseUrl(), null, null, null, 5);

    Map<String, Object> result = client.request("GET", "/status", null);

    assertEquals(200, result.get("status"));
  }

  @Test
  void requestRejectsCallerSuppliedAuthenticationHeaders() {
    HopWebClient client = new HopWebClient(baseUrl(), null, null, null, 5);

    assertThrows(
        IllegalArgumentException.class,
        () -> client.request("GET", "/status", Map.of("Authorization", "Bearer secret")));
  }

  @Test
  void rejectsMutatingAndUnknownGetAndHeadEndpointsBeforeHttp() {
    HopWebClient client = new HopWebClient(baseUrl(), null, null, null, 5);
    for (String method : List.of("GET", "HEAD")) {
      for (String path :
          List.of(
              "startPipeline?name=orders",
              "execPipeline?file=orders.hpl",
              "startWorkflow",
              "execWorkflow",
              "stopPipeline",
              "stopWorkflow",
              "removePipeline",
              "removeWorkflow",
              "getExecInfo?type=DELETE&location=local&id=run",
              "getExecInfo?type=STATE",
              "unknownPluginEndpoint",
              "status/extra",
              "Status",
              "")) {
        McpException failure =
            assertThrows(
                McpException.class,
                () -> client.request(method, path.isEmpty() ? "/" : path, Map.of()),
                path);
        assertEquals("WEB_ENDPOINT_NOT_READ_ONLY", failure.code());
      }
    }
    assertEquals(0, requestsReceived.get());
  }

  @Test
  void rejectsAlternatePathInterpretationsAndRoutingHeadersBeforeHttp() {
    HopWebClient client = new HopWebClient(baseUrl(), null, null, null, 5);
    for (String path :
        List.of(
            "status;endpoint=startPipeline",
            "status/../startPipeline",
            "./status",
            "%73tatus",
            "status%3b/../startPipeline",
            "status//../startPipeline",
            "%2573tatus",
            "hop//status",
            "status%00",
            "status%3Ftype=DELETE")) {
      assertThrows(
          IllegalArgumentException.class, () -> client.request("GET", path, Map.of()), path);
    }
    for (String header :
        List.of("X-Original-URL", "X-Rewrite-URL", "X-Forwarded-Uri", "X-HTTP-Method-Override")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> client.request("GET", "status", Map.of(header, "/hop/startPipeline")),
          header);
    }
    assertEquals(0, requestsReceived.get());
  }

  @Test
  void authorizesTheCompleteNativePathIncludingTheConfiguredBase() throws Exception {
    String origin = "http://127.0.0.1:" + server.getAddress().getPort();
    HopWebClient unsafeBase = new HopWebClient(origin + "/hop/startPipeline", null, null, null, 5);
    assertThrows(McpException.class, () -> unsafeBase.request("GET", "status", Map.of()));
    HopWebClient unknownBase = new HopWebClient(origin + "/api", null, null, null, 5);
    assertThrows(McpException.class, () -> unknownBase.request("GET", "status", Map.of()));
    assertEquals(0, requestsReceived.get());

    HopWebClient prefixed = new HopWebClient(origin + "/proxy/hop", null, null, null, 5);
    for (String endpoint : List.of("status", "pipelineStatus", "workflowStatus")) {
      assertEquals(200, prefixed.request("GET", endpoint, Map.of()).get("status"));
      assertEquals("/proxy/hop/" + endpoint, requestPath.get());
    }
    HopWebClient originBase = new HopWebClient(origin, null, null, null, 5);
    assertEquals(200, originBase.request("GET", "hop/status", Map.of()).get("status"));
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/hop";
  }
}
