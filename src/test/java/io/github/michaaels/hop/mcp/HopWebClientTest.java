package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
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

  @BeforeEach
  void setUp() throws Exception {
    requestPath = new AtomicReference<>();
    authorization = new AtomicReference<>();
    redirectTargetRequests = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/hop",
        exchange -> {
          requestPath.set(exchange.getRequestURI().toString());
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          String path = exchange.getRequestURI().getPath();
          if ("/hop/redirect".equals(path)) {
            exchange.getResponseHeaders().add("Location", "/hop/final");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
            return;
          }
          if ("/hop/final".equals(path)) {
            redirectTargetRequests.incrementAndGet();
          }
          if ("/hop/large".equals(path)) {
            exchange.getResponseHeaders().add("X-Large", "h".repeat(4096));
            byte[] largeResponse = new byte[HopWebClient.MAX_RESPONSE_READ_BYTES + 1024];
            Arrays.fill(largeResponse, (byte) 'a');
            exchange.sendResponseHeaders(200, largeResponse.length);
            exchange.getResponseBody().write(largeResponse);
            exchange.close();
            return;
          }
          if ("/hop/unicode".equals(path)) {
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

    Map<String, Object> result = client.request("GET", "/redirect", Map.of());

    assertEquals(302, result.get("status"));
    assertEquals("/hop/redirect", requestPath.get());
    assertEquals(0, redirectTargetRequests.get());
  }

  @Test
  void requestBoundsReturnedBodyAndLargeResponseHeaders() throws Exception {
    HopWebClient client = new HopWebClient(baseUrl(), null, null, null, 5);

    Map<String, Object> result = client.request("GET", "/large", Map.of());

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

    Map<String, Object> result = client.request("GET", "/unicode", Map.of());

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

  private String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/hop";
  }
}
