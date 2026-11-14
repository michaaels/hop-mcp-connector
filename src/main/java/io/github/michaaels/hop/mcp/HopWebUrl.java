package io.github.michaaels.hop.mcp;

import java.net.URI;

/** URL rules shared by the agent client and the Web endpoint; no UI/runtime dependency. */
final class HopWebUrl {
  private HopWebUrl() {}

  static void requirePublicUi(URI uri) {
    if (uri == null
        || uri.getHost() == null
        || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || uri.getPath() == null
        || uri.getPath().isEmpty()
        || uri.getPath().endsWith("/")
        || uri.getPath().contains(";")) {
      throw new IllegalArgumentException(
          "Use the public Hop Web UI URL without credentials or session parameters");
    }
    if (!"https".equalsIgnoreCase(uri.getScheme())
        && !("http".equalsIgnoreCase(uri.getScheme()) && isLoopback(uri.getHost()))) {
      throw new IllegalArgumentException(
          "Remote MCP requires HTTPS; HTTP is allowed only for loopback tests");
    }
  }

  static boolean isLoopback(String host) {
    return "127.0.0.1".equals(host)
        || "localhost".equalsIgnoreCase(host)
        || "::1".equals(host)
        || "[::1]".equals(host)
        || "0:0:0:0:0:0:0:1".equals(host);
  }
}
