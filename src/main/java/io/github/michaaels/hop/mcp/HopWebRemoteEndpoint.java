package io.github.michaaels.hop.mcp;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.eclipse.rap.rwt.RWT;
import org.eclipse.rap.rwt.service.ServiceHandler;

/** Routes the existing RAP servlet to an explicitly active Hop Web session. */
final class HopWebRemoteEndpoint implements ServiceHandler {
  private static final String ATTRIBUTE = HopWebRemoteEndpoint.class.getName();
  private static final int MAX_CONNECTIONS = 8;
  private final Map<String, HopRemoteMcpConnection> connections = new HashMap<>();
  private final Semaphore permits = new Semaphore(8);

  static HopWebRemoteEndpoint current() {
    var application = RWT.getApplicationContext();
    synchronized (application) {
      var existing = (HopWebRemoteEndpoint) application.getAttribute(ATTRIBUTE);
      if (existing != null) return existing;
      var endpoint = new HopWebRemoteEndpoint();
      application
          .getServiceManager()
          .registerServiceHandler(HopRemoteMcpConnection.HANDLER, endpoint);
      application.setAttribute(ATTRIBUTE, endpoint);
      return endpoint;
    }
  }

  synchronized void add(HopRemoteMcpConnection connection) {
    connections.values().removeIf(HopRemoteMcpConnection::isClosed);
    if (connections.size() >= MAX_CONNECTIONS) {
      connection.close();
      throw new IllegalStateException("The Hop Web MCP connection limit has been reached");
    }
    connections.put(connection.id(), connection);
  }

  @Override
  public void service(HttpServletRequest request, HttpServletResponse response)
      throws IOException, ServletException {
    // RWT creates an HTTP session even for service handlers. Never leak one per agent request,
    // and never invalidate an existing browser session supplied by the caller.
    boolean freshSession = !request.isRequestedSessionIdValid();
    try {
      if (!"POST".equals(request.getMethod()) && !"DELETE".equals(request.getMethod())) {
        response.setHeader("Allow", "POST, DELETE");
        response.setStatus(405);
        return;
      }
      HopRemoteMcpConnection connection = select(request, response);
      if (connection == null) return;
      if (!permits.tryAcquire()) {
        response.setHeader("Retry-After", "1");
        response.setStatus(429);
        return;
      }
      try {
        connection.service(request, response);
      } finally {
        permits.release();
      }
    } finally {
      var session = request.getSession(false);
      if (freshSession && session != null) session.invalidate();
    }
  }

  private synchronized HopRemoteMcpConnection select(
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    response.setHeader("Cache-Control", "no-store");
    var ids = request.getParameterValues("session");
    String bound = request.getHeader("Mcp-Session-Id");
    if (ids != null && ids.length != 1) {
      response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      return null;
    }
    if (ids != null) {
      var connection = connections.get(ids[0]);
      if (connection != null && !connection.isClosed()) return connection;
      response.setStatus(404);
      return null;
    }
    if (bound != null) {
      var connection =
          connections.values().stream().filter(c -> c.ownsBinding(bound)).findFirst().orElse(null);
      if (connection != null && !connection.isClosed()) return connection;
      HopRemoteMcpConnection.error(
          response, 404, "LIVE_SYNC_INACTIVE", "The Live Sync session ended");
      return null;
    }
    var active =
        connections.values().stream()
            .filter(connection -> !connection.isClosed())
            .sorted(java.util.Comparator.comparing(HopRemoteMcpConnection::id))
            .toList();
    if (!active.isEmpty()) {
      active = active.stream().filter(c -> c.trustedRequest(request)).toList();
      if (active.isEmpty()) {
        response.setStatus(403);
        return null;
      }
    }
    if (active.size() == 1) return active.getFirst();
    response.setStatus(
        active.isEmpty()
            ? HttpServletResponse.SC_SERVICE_UNAVAILABLE
            : HttpServletResponse.SC_CONFLICT);
    response.setContentType("application/json");
    response
        .getWriter()
        .write(
            JsonUtil.toJson(
                Map.of(
                    "code",
                    active.isEmpty() ? "LIVE_SYNC_INACTIVE" : "SESSION_SELECTION_REQUIRED",
                    "sessions",
                    active.stream().map(HopRemoteMcpConnection::description).toList(),
                    "error",
                    active.isEmpty()
                        ? "Activate MCP Live Sync in Hop Web before connecting the agent"
                        : "Multiple Hop Web sessions are active; use the connection URL copied from the intended session")));
    return null;
  }
}
