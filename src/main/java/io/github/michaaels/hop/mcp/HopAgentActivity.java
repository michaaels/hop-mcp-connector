package io.github.michaaels.hop.mcp;

/** User-facing activity; contains no project paths, scripts, SQL or credentials. */
record HopAgentActivity(
    boolean connected,
    boolean working,
    String agent,
    String version,
    String operation,
    long elapsedMillis,
    long lastActivityMillis) {
  static HopAgentActivity disconnected() {
    return new HopAgentActivity(false, false, "No conectado", "", "En espera", 0, 0);
  }
}
