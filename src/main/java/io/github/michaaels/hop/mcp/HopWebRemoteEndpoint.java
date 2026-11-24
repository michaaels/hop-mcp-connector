package io.github.michaaels.hop.mcp;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.eclipse.rap.rwt.RWT;
import org.eclipse.rap.rwt.service.ServiceHandler;

/** Routes the existing RAP servlet to an explicitly active Hop Web session. */
final class HopWebRemoteEndpoint implements ServiceHandler {
  private static final String ATTRIBUTE = HopWebRemoteEndpoint.class.getName();
  private static final int MAX_CONNECTIONS = 8;
  private final List<HopRemoteMcpConnection> connections = new ArrayList<>();
  private final Semaphore permits = new Semaphore(8);
  private final HopWebProjectWork work = new HopWebProjectWork();

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
    connections.removeIf(HopRemoteMcpConnection::isClosed);
    if (connections.size() >= MAX_CONNECTIONS) {
      connection.close();
      throw new IllegalStateException("The Hop Web MCP connection limit has been reached");
    }
    connection.registerWork(work);
    connections.add(connection);
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
    if (bound != null) {
      String sessionId = ids == null ? null : ids[0];
      var connection =
          connections.stream()
              .filter(c -> c.matchesBinding(bound, sessionId))
              .findFirst()
              .orElse(null);
      if (connection != null) return connection;
      HopRemoteMcpConnection.error(
          response, 404, "AGENT_DISCONNECTED", "The agent connection is no longer available");
      return null;
    }
    String pairing = request.getHeader(HopWebAgentSession.PAIRING_HEADER);
    if (ids == null
        && "POST".equals(request.getMethod())
        && HopWebAgentSession.isPairingCode(pairing)) {
      for (var connection : connections) {
        if (connection.trustedRequest(request) && connection.acceptsPairing(pairing))
          return connection;
      }
    }
    HopRemoteMcpConnection.pairingRequired(response);
    return null;
  }
}
