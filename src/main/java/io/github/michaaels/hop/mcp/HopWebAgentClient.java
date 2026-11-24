package io.github.michaaels.hop.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** URL-based MCP client. It never opens a local or shared Hop project. */
final class HopWebAgentClient implements AutoCloseable {
  private static final int MAX_BYTES = 512 * 1024;
  private static final String PAIRING_MESSAGE =
      "Usa Connect Agent para copiar un enlace nuevo desde la ventana de Hop Web que quieres usar.";
  private final Duration timeout;
  private final HttpClient http;
  private final McpJsonMapper mapper = new JacksonMcpJsonMapperSupplier().get();
  private URI endpoint;
  private String session;
  private long sequence;
  private List<Map<String, Object>> tools = List.of();
  private Map<String, String> agentInfo = Map.of("name", "No informado", "version", "");
  private final java.util.concurrent.ScheduledExecutorService heartbeat =
      java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
          task -> {
            var thread = new Thread(task, "hop-mcp-web-heartbeat");
            thread.setDaemon(true);
            return thread;
          });
  private Map<String, Object> state =
      Map.of(
          "connected",
          false,
          "state",
          "disconnected",
          "message",
          "MCP Live Sync no está conectado");

  HopWebAgentClient(Path additionalCertificate) throws Exception {
    this(additionalCertificate, Duration.ofSeconds(30));
  }

  HopWebAgentClient(Path additionalCertificate, Duration timeout) throws Exception {
    if (timeout.isNegative()
        || timeout.isZero()
        || timeout.compareTo(Duration.ofSeconds(120)) > 0) {
      throw new IllegalArgumentException("Use a positive timeout of at most 120 seconds");
    }
    this.timeout = timeout;
    var builder =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER);
    if (additionalCertificate != null) builder.sslContext(trustCertificate(additionalCertificate));
    http = builder.build();
    heartbeat.scheduleWithFixedDelay(this::heartbeat, 10, 10, TimeUnit.SECONDS);
  }

  synchronized void agentInfo(McpSchema.Implementation reported) {
    if (reported == null) return;
    agentInfo =
        Map.of("name", bounded(reported.name(), 100), "version", bounded(reported.version(), 100));
  }

  synchronized Map<String, Object> connect(String webUrl) {
    final URI link;
    final URI target;
    try {
      link = parseUrl(webUrl);
      target = endpoint(link);
    } catch (IllegalArgumentException failure) {
      return Map.of(
          "connected", false, "state", "invalid_url", "message", "Usa una URL válida de Hop Web.");
    }
    String pairing = pairingCode(link);
    if (pairing == null || !editorId(target).isEmpty()) {
      return Map.of("connected", false, "state", "pairing_required", "message", PAIRING_MESSAGE);
    }
    release();
    endpoint = target;
    session = null;
    tools = List.of();
    try {
      var initialized =
          rpc(
              "initialize",
              Map.of(
                  "protocolVersion",
                  "2025-11-25",
                  "capabilities",
                  Map.of(),
                  "clientInfo",
                  Map.of("name", "hop-web-agent", "version", HopMcpVersion.current())),
              timeout,
              pairing);
      if (!"2025-11-25".equals(initialized.get("protocolVersion")))
        throw new IOException("Unsupported MCP protocol");
      if (!"hop-mcp-connector".equals(map(initialized.get("serverInfo")).get("name"))) {
        throw new IOException("The URL is not the Hop Web MCP service");
      }
      Map<String, Object> config = callData("hop_config", Map.of());
      Map<String, Object> live = callData("hop_live_ui_status", Map.of());
      if (!Boolean.TRUE.equals(live.get("available"))) throw new RemoteUnavailable(503);
      tools = list(rpc("tools/list", Map.of()).get("tools"));
      if (tools == null || tools.size() > 200) throw new IOException("Invalid remote tool catalog");
      state =
          Map.ofEntries(
              Map.entry("ui_url", uiUrl(endpoint)),
              Map.entry("project_name", config.getOrDefault("project_name", "")),
              Map.entry(
                  "presentation_default", config.getOrDefault("presentation_default", "guided")),
              Map.entry("session_id", editorId(endpoint)),
              Map.entry("connected", true),
              Map.entry("state", "connected"),
              Map.entry("web_url", endpoint.toASCIIString()),
              Map.entry("project_root", config.get("project_root")),
              Map.entry("allow_mutation", config.get("allow_mutation")),
              Map.entry("message", "Conectado a Hop Web mediante MCP Live Sync"));
    } catch (Exception failure) {
      release();
      disconnect(failure);
    }
    return state;
  }

  synchronized Map<String, Object> status() {
    if (Boolean.TRUE.equals(state.get("connected"))) {
      try {
        rpc("ping", Map.of());
      } catch (Exception failure) {
        disconnect(failure);
      }
    }
    return state;
  }

  synchronized void heartbeat() {
    if (!Boolean.TRUE.equals(state.get("connected"))) return;
    try {
      rpc("ping", Map.of(), Duration.ofSeconds(5));
    } catch (RemoteUnavailable failure) {
      if (failure.status != 429) disconnect(failure);
    } catch (Exception failure) {
      disconnect(failure);
    }
  }

  synchronized Map<String, Object> tools(String query, int offset, int limit) {
    requireConnected();
    if (offset < 0 || limit < 1 || limit > 20)
      throw new IllegalArgumentException("Use offset >= 0 and limit 1-20");
    String filter = query == null ? "" : query.toLowerCase(java.util.Locale.ROOT);
    var matches =
        tools.stream()
            .filter(
                tool ->
                    String.valueOf(tool.get("name"))
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains(filter))
            .toList();
    int from = Math.min(offset, matches.size());
    int to = Math.min(from + limit, matches.size());
    return Map.of(
        "tools",
        matches.subList(from, to),
        "count",
        matches.size(),
        "offset",
        offset,
        "has_more",
        to < matches.size());
  }

  synchronized McpSchema.CallToolResult call(String name, Map<String, Object> arguments)
      throws Exception {
    requireConnected();
    if (tools.stream().noneMatch(tool -> name.equals(tool.get("name"))))
      throw new IllegalArgumentException("The remote tool is not available; inspect hop_web_tools");
    try {
      var result = rpc("tools/call", Map.of("name", name, "arguments", arguments));
      return mapper.readValue(JsonUtil.toJson(result), McpSchema.CallToolResult.class);
    } catch (Exception failure) {
      disconnect(failure);
      throw new IllegalStateException(String.valueOf(state.get("message")));
    }
  }

  private Map<String, Object> callData(String name, Map<String, Object> args) throws Exception {
    var result = rpc("tools/call", Map.of("name", name, "arguments", args));
    if (Boolean.TRUE.equals(result.get("isError")))
      throw new IOException("Remote MCP rejected the request");
    return map(result.get("structuredContent"));
  }

  private Map<String, Object> rpc(String method, Map<String, Object> params) throws Exception {
    return rpc(method, params, timeout);
  }

  private Map<String, Object> rpc(String method, Map<String, Object> params, Duration deadline)
      throws Exception {
    return rpc(method, params, deadline, null);
  }

  private Map<String, Object> rpc(
      String method, Map<String, Object> params, Duration deadline, String pairing)
      throws Exception {
    if (endpoint == null) throw new IllegalStateException("MCP Live Sync no está conectado");
    byte[] body =
        JsonUtil.toJson(
                Map.of("jsonrpc", "2.0", "id", ++sequence, "method", method, "params", params))
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    if (body.length > MAX_BYTES) throw new IllegalArgumentException("MCP request exceeds 512 KiB");
    var request =
        HttpRequest.newBuilder(endpoint)
            .timeout(deadline)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", "2025-11-25");
    request.header(
        "Hop-Mcp-Agent",
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                JsonUtil.toJson(agentInfo).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    if (session != null) request.header("Mcp-Session-Id", session);
    if (pairing != null) request.header(HopWebAgentSession.PAIRING_HEADER, pairing);
    var pending =
        http.sendAsync(
            request.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
            ignored -> new BoundedBody());
    HttpResponse<byte[]> response;
    try {
      response = pending.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
    } finally {
      if (!pending.isDone()) pending.cancel(true);
    }
    if (response.statusCode() != 200) {
      Map<String, Object> detail = Map.of();
      try {
        detail = map(mapper.readValue(response.body(), Map.class));
      } catch (Exception ignored) {
        /* Non-JSON proxy errors retain their HTTP status. */
      }
      throw new RemoteUnavailable(response.statusCode(), detail);
    }
    String contentType = response.headers().firstValue("Content-Type").orElse("");
    if (!contentType.startsWith("application/json"))
      throw new IOException("The URL did not return MCP JSON");
    String selected = response.headers().firstValue("Mcp-Session-Id").orElse(null);
    String editor = response.headers().firstValue("Hop-Ui-Session-Id").orElse(null);
    if (pairing != null) {
      if (selected == null || editor == null) throw new IOException("Missing MCP pairing response");
      session = java.util.UUID.fromString(selected).toString();
      endpoint = select(endpoint, editor);
      if (!"true".equals(response.headers().firstValue("Hop-Mcp-Pairing-Used").orElse(null)))
        throw new IOException("The remote Web server does not support one-time pairing");
    } else if (!java.util.Objects.equals(session, selected) || !editorId(endpoint).equals(editor)) {
      throw new IOException("The remote Web session changed");
    }
    Map<String, Object> envelope = map(mapper.readValue(response.body(), Map.class));
    if (envelope.containsKey("error")) throw new IOException("Remote MCP protocol error");
    return map(envelope.get("result"));
  }

  private static URI select(URI endpoint, String id) {
    String selected = java.util.UUID.fromString(id).toString();
    return URI.create(
        endpoint.toASCIIString().replaceAll("&session=[^&]*", "") + "&session=" + selected);
  }

  private static String editorId(URI endpoint) {
    for (String part : endpoint.getRawQuery().split("&"))
      if (part.startsWith("session=")) return part.substring(8);
    return "";
  }

  private static String uiUrl(URI endpoint) {
    String id = editorId(endpoint);
    String base = endpoint.toASCIIString().split("\\?", 2)[0];
    return id.isEmpty() ? base : base + "?session=" + id;
  }

  private static String bounded(String value, int length) {
    if (value == null) return "";
    return value.substring(0, Math.min(value.length(), length));
  }

  private void release() {
    if (session == null || endpoint == null) return;
    try {
      var request =
          HttpRequest.newBuilder(endpoint)
              .timeout(Duration.ofSeconds(2))
              .header("Mcp-Session-Id", session)
              .DELETE()
              .build();
      http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).get(2, TimeUnit.SECONDS);
    } catch (Exception ignored) {
      /* Presence also expires if the agent cannot release its lease. */
    }
  }

  private static URI parseUrl(String value) {
    if (value == null || value.length() > 2048)
      throw new IllegalArgumentException("Supply the Hop Web URL");
    return URI.create(value.trim());
  }

  private static String pairingCode(URI ui) {
    String fragment = ui.getRawFragment();
    if (fragment == null || !fragment.startsWith("pair=")) return null;
    String code = fragment.substring(5);
    return HopWebAgentSession.isPairingCode(code) ? code : null;
  }

  static URI endpoint(String value) {
    return endpoint(parseUrl(value));
  }

  private static URI endpoint(URI ui) {
    if (ui.getRawFragment() != null && pairingCode(ui) == null)
      throw new IllegalArgumentException("Use the Hop Web UI URL or the copied connection link");
    String selected = null;
    if (ui.getRawQuery() != null) {
      for (String entry : ui.getRawQuery().split("&")) {
        if (entry.equals("servicehandler=hop-mcp")) continue;
        if (entry.startsWith("project=") || entry.startsWith("file=")) continue;
        if (entry.startsWith("session=") && selected == null) {
          selected = java.util.UUID.fromString(entry.substring(8)).toString();
        } else throw new IllegalArgumentException("Use the Hop Web UI URL or its MCP session URL");
      }
    }
    try {
      URI clean =
          new URI(
              ui.getScheme(),
              ui.getRawUserInfo(),
              ui.getHost(),
              ui.getPort(),
              ui.getPath(),
              null,
              null);
      HopWebUrl.requirePublicUi(clean);
      return URI.create(
          clean.toASCIIString()
              + "?servicehandler=hop-mcp"
              + (selected == null ? "" : "&session=" + selected));
    } catch (java.net.URISyntaxException invalid) {
      throw new IllegalArgumentException("Invalid Hop Web URL");
    }
  }

  private void disconnect(Exception failure) {
    String category = "unreachable";
    String message = "No se pudo conectar a Hop Web";
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof javax.net.ssl.SSLException) {
        category = "tls_error";
        message = "El certificado HTTPS de Hop Web no es de confianza para este agente";
        break;
      }
      if (cause instanceof RemoteUnavailable unavailable) {
        switch (unavailable.status) {
          case 404, 503 -> {
            boolean revoked = "AGENT_DISCONNECTED".equals(unavailable.detail.get("code"));
            category = revoked ? "disconnected" : "inactive";
            message =
                revoked
                    ? "Hop Web desconectó al agente; puedes volver a conectarte"
                    : "MCP Live Sync no está activo o la sesión ya no está disponible";
          }
          case 401, 403 -> {
            boolean pairing = "PAIRING_REQUIRED".equals(unavailable.detail.get("code"));
            category = pairing ? "pairing_required" : "access_denied";
            message =
                pairing
                    ? PAIRING_MESSAGE
                    : "Hop Web rechazó el acceso; revisa la protección del sitio";
          }
          default -> {
            category = "protocol_error";
            message = "Hop Web no respondió como un servicio MCP disponible";
          }
        }
      }
    }
    state = Map.of("connected", false, "state", category, "message", message);
    endpoint = null;
    session = null;
    tools = List.of();
  }

  private void requireConnected() {
    if (!Boolean.TRUE.equals(state.get("connected")))
      throw new IllegalStateException(String.valueOf(state.get("message")));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> list(Object value) {
    return (List<Map<String, Object>>) value;
  }

  private static SSLContext trustCertificate(Path certificate) throws Exception {
    byte[] bytes;
    try (var input = Files.newInputStream(certificate)) {
      bytes = input.readNBytes(65537);
    }
    if (bytes.length > 65536)
      throw new IllegalArgumentException("Additional certificate exceeds 64 KiB");
    var store = KeyStore.getInstance(KeyStore.getDefaultType());
    store.load(null, null);
    var defaults = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    defaults.init((KeyStore) null);
    int index = 0;
    for (var manager : defaults.getTrustManagers()) {
      if (manager instanceof X509TrustManager x509) {
        for (var issuer : x509.getAcceptedIssuers())
          store.setCertificateEntry("default-" + index++, issuer);
      }
    }
    var certificates =
        CertificateFactory.getInstance("X.509")
            .generateCertificates(new ByteArrayInputStream(bytes));
    if (certificates.isEmpty() || certificates.size() > 8)
      throw new IllegalArgumentException("Supply 1-8 public X.509 certificates");
    for (var trusted : certificates) store.setCertificateEntry("hop-" + index++, trusted);
    var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    factory.init(store);
    var ssl = SSLContext.getInstance("TLS");
    ssl.init(null, factory.getTrustManagers(), null);
    return ssl;
  }

  @Override
  public void close() {
    heartbeat.shutdownNow();
    synchronized (this) {
      release();
    }
    http.close();
  }

  private static final class RemoteUnavailable extends IOException {
    final int status;
    final Map<String, Object> detail;

    RemoteUnavailable(int status) {
      this(status, Map.of());
    }

    RemoteUnavailable(int status, Map<String, Object> detail) {
      super("Hop Web HTTP " + status);
      this.status = status;
      this.detail = detail == null ? Map.of() : detail;
    }
  }

  private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Flow.Subscription subscription;

    @Override
    public CompletionStage<byte[]> getBody() {
      return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription value) {
      subscription = value;
      value.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      for (var buffer : buffers) {
        if (buffer.remaining() > MAX_BYTES - bytes.size()) {
          subscription.cancel();
          body.completeExceptionally(new IOException("MCP response exceeds 512 KiB"));
          return;
        }
        byte[] chunk = new byte[buffer.remaining()];
        buffer.get(chunk);
        bytes.writeBytes(chunk);
      }
      subscription.request(1);
    }

    @Override
    public void onError(Throwable failure) {
      body.completeExceptionally(failure);
    }

    @Override
    public void onComplete() {
      body.complete(bytes.toByteArray());
    }
  }
}
