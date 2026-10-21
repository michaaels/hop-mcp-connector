package io.github.michaaels.hop.mcp;

import java.net.URI;
import java.util.Set;

/** Authorizes operations, not HTTP verbs: several Hop GET endpoints execute or delete work. */
final class HopWebReadPolicy {
  private static final Set<String> READ_ENDPOINTS =
      Set.of("status", "pipelineStatus", "workflowStatus");

  private HopWebReadPolicy() {}

  static void requireReadTarget(URI target) throws McpException {
    String path = target.getRawPath();
    requireCanonicalPath(path);
    int endpointStart = path.lastIndexOf('/');
    String endpoint = path.substring(endpointStart + 1);
    String context = path.substring(0, endpointStart);
    if (!context.endsWith("/hop") || !READ_ENDPOINTS.contains(endpoint)) {
      // In particular, getExecInfo is not a read endpoint: type=DELETE mutates its location.
      throw McpException.security(
          "WEB_ENDPOINT_NOT_READ_ONLY",
          "Only Hop status, pipelineStatus and workflowStatus endpoints are allowed");
    }
  }

  static void requireCanonicalPath(String path) {
    if (path == null || path.isEmpty()) return;
    // Native endpoint names are literal ASCII. Reject alternative container/proxy interpretations
    // instead of decoding percent escapes or stripping matrix parameters before authorization.
    if (path.contains("//") || !path.matches("[A-Za-z0-9._~/\\-]*")) {
      throw new IllegalArgumentException("REST path must use literal canonical path segments");
    }
    for (String segment : path.split("/")) {
      if (segment.equals(".") || segment.equals("..")) {
        throw new IllegalArgumentException("REST path cannot contain traversal segments");
      }
    }
  }
}
