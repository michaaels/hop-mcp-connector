package io.github.michaaels.hop.mcp;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** A project-bound MCP connection, available only while its owner enables MCP Live Sync. */
final class HopRemoteMcpConnection implements AutoCloseable {
  static final int MAX_REQUEST_BYTES = 512 * 1024;
  static final String HANDLER = "hop-mcp";
  private final String id = UUID.randomUUID().toString();
  private final URI publicUi;
  private final HttpServletStatelessServerTransport transport;
  private final HopMcpServer server;
  private final Semaphore permits = new Semaphore(2);
  private final AtomicInteger inFlight = new AtomicInteger();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicBoolean disposed = new AtomicBoolean();
  private final java.util.concurrent.CompletableFuture<Void> disposal =
      new java.util.concurrent.CompletableFuture<>();
  private final HopWebAgentSession agent = new HopWebAgentSession();
  private volatile String project;
  private volatile String file = "";

  static HopRemoteMcpConnection open(HopMcpService service, URI publicUi) {
    try {
      requirePublicUi(publicUi);
      return new HopRemoteMcpConnection(service, publicUi);
    } catch (RuntimeException failure) {
      service.close();
      throw failure;
    }
  }

  private HopRemoteMcpConnection(HopMcpService service, URI publicUi) {
    this.publicUi = publicUi;
    project =
        java.nio.file.Path.of(String.valueOf(service.config().get("project_root")))
            .getFileName()
            .toString();
    transport =
        HttpServletStatelessServerTransport.builder()
            .jsonMapper(new JacksonMcpJsonMapperSupplier().get())
            .messageEndpoint(publicUi.getPath())
            .contextExtractor(
                request ->
                    io.modelcontextprotocol.common.McpTransportContext.create(
                        java.util.Map.of(
                            "hop_agent_binding", request.getAttribute("hop_agent_binding"))))
            .maxRequestSize(MAX_REQUEST_BYTES)
            .build();
    server =
        HopMcpServer.withHttpTransport(
            service,
            transport,
            (context, name) -> agent.begin((String) context.get("hop_agent_binding"), name));
  }

  String id() {
    return id;
  }

  String endpoint() {
    return publicUi.toASCIIString() + "?servicehandler=" + HANDLER;
  }

  String webUrl() {
    return publicUi.toASCIIString();
  }

  String sessionEndpoint() {
    return endpoint() + "&session=" + id;
  }

  boolean isClosed() {
    return closed.get();
  }

  boolean isWorking() {
    return !closed.get() && agent.activity().working();
  }

  HopAgentActivity activity() {
    return agent.activity();
  }

  long connectedSince() {
    return agent.connectedSince();
  }

  boolean acceptsBinding(String binding) {
    return !closed.get() && agent.accepts(binding);
  }

  boolean ownsBinding(String binding) {
    return agent.owns(binding);
  }

  void disconnectAgent() {
    agent.disconnect();
  }

  void editor(String name, String path) {
    project = bounded(name, 200);
    file = bounded(path, 4096);
  }

  java.util.Map<String, Object> description() {
    var state = activity();
    return java.util.Map.of(
        "session_id",
        id,
        "project",
        project,
        "file",
        file,
        "connected",
        state.connected(),
        "agent",
        state.connected() ? state.agent() : "");
  }

  private static String bounded(String text, int maximum) {
    if (text == null) return "";
    return text.substring(0, Math.min(text.length(), maximum));
  }

  void service(HttpServletRequest request, HttpServletResponse response)
      throws IOException, ServletException {
    response.setHeader("Cache-Control", "no-store");
    if (closed.get()) {
      response.setStatus(HttpServletResponse.SC_NOT_FOUND);
      return;
    }
    if (!trustedRequest(request)) {
      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
      return;
    }
    if (!"POST".equals(request.getMethod()) && !"DELETE".equals(request.getMethod())) {
      response.setHeader("Allow", "POST, DELETE");
      response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
      return;
    }
    if (!permits.tryAcquire()) {
      response.setHeader("Retry-After", "1");
      response.setStatus(429);
      return;
    }
    inFlight.incrementAndGet();
    try {
      if (closed.get()) {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        return;
      }
      String binding = request.getHeader("Mcp-Session-Id");
      if ("DELETE".equals(request.getMethod())) {
        if (!agent.disconnect(binding)) {
          error(response, 404, "AGENT_DISCONNECTED", "The agent connection is no longer available");
          return;
        }
        response.setStatus(204);
        return;
      }
      post(request, response);
    } finally {
      permits.release();
      if (inFlight.decrementAndGet() == 0 && closed.get()) dispose();
    }
  }

  private void post(HttpServletRequest request, HttpServletResponse response)
      throws IOException, ServletException {
    var accepted = bind(request, response);
    if (accepted == null) return;
    response.setHeader("Mcp-Session-Id", accepted.binding());
    response.setHeader("Hop-Ui-Session-Id", id);
    accepted.request().setAttribute("hop_agent_binding", accepted.binding());
    try {
      transport.service(accepted.request(), response);
    } catch (IOException | ServletException | RuntimeException failure) {
      if (accepted.created()) agent.disconnect(accepted.binding());
      throw failure;
    }
    if (accepted.created() && response.getStatus() >= 400) agent.disconnect(accepted.binding());
  }

  private BoundRequest bind(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String binding = request.getHeader("Mcp-Session-Id");
    if (binding != null) {
      if (agent.touch(binding)) return new BoundRequest(request, binding, false);
      error(
          response, 404, "AGENT_DISCONNECTED", "The editor disconnected this agent; connect again");
      return null;
    }
    var identity = identity(request.getHeader("Hop-Mcp-Agent"));
    if (request.getHeader("Hop-Mcp-Agent") == null) {
      var initial = new HopMcpInitialRequest(request);
      if (initial.oversized()) {
        response.setStatus(413);
        return null;
      }
      identity = initial.identity();
      request = initial;
    }
    binding = agent.connect(identity.get("name"), identity.get("version"));
    if (binding == null) {
      error(response, 409, "SESSION_BUSY", "This editor already has a connected agent");
      return null;
    }
    return new BoundRequest(request, binding, true);
  }

  private record BoundRequest(HttpServletRequest request, String binding, boolean created) {}

  boolean trustedRequest(HttpServletRequest request) {
    // TLS termination is accepted only from a local reverse proxy, never from a remote peer.
    if (!request.isSecure() && !HopWebUrl.isLoopback(request.getRemoteAddr())) return false;
    String host = request.getHeader("Host");
    if (host == null || !host.equalsIgnoreCase(publicUi.getRawAuthority())) return false;
    String origin = request.getHeader("Origin");
    if (origin == null) return true;
    try {
      URI candidate = URI.create(origin);
      return candidate.getRawUserInfo() == null
          && candidate.getRawQuery() == null
          && candidate.getRawFragment() == null
          && candidate.getPath() != null
          && (candidate.getPath().isEmpty() || "/".equals(candidate.getPath()))
          && publicUi.getScheme().equalsIgnoreCase(candidate.getScheme())
          && publicUi.getRawAuthority().equalsIgnoreCase(candidate.getRawAuthority());
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  @SuppressWarnings("unchecked")
  private static java.util.Map<String, String> identity(String header) {
    if (header == null || header.length() > 1024) return java.util.Map.of();
    try {
      var bytes = java.util.Base64.getUrlDecoder().decode(header);
      var data = new JacksonMcpJsonMapperSupplier().get().readValue(bytes, java.util.Map.class);
      return java.util.Map.of(
          "name",
          String.valueOf(data.getOrDefault("name", "No informado")),
          "version",
          String.valueOf(data.getOrDefault("version", "")));
    } catch (Exception invalid) {
      return java.util.Map.of();
    }
  }

  static void error(HttpServletResponse response, int status, String code, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.getWriter().write(JsonUtil.toJson(java.util.Map.of("code", code, "error", message)));
  }

  static void requirePublicUi(URI uri) {
    HopWebUrl.requirePublicUi(uri);
  }

  @Override
  public void close() {
    closed.set(true);
    disconnectAgent();
    if (inFlight.get() == 0) dispose();
  }

  private void dispose() {
    if (disposed.compareAndSet(false, true)) {
      try {
        server.close();
        disposal.complete(null);
      } catch (RuntimeException failure) {
        disposal.completeExceptionally(failure);
        throw failure;
      }
    }
  }

  void awaitDisposal(java.time.Duration timeout) throws Exception {
    disposal.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
  }
}
