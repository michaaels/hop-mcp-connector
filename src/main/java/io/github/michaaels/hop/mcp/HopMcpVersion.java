package io.github.michaaels.hop.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class HopMcpVersion {
  private static final String RESOURCE = "/hop-mcp-version.txt";
  private static final String VERSION = load();

  private HopMcpVersion() {}

  static String current() {
    return VERSION;
  }

  private static String load() {
    try (InputStream input = HopMcpVersion.class.getResourceAsStream(RESOURCE)) {
      if (input == null) return "development";
      String version = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
      return version.isEmpty() || version.contains("${") ? "development" : version;
    } catch (IOException ignored) {
      return "development";
    }
  }
}
