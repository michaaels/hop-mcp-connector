package io.github.michaaels.hop.mcp;

import java.io.IOException;

/** A safe, typed error contract for expected MCP operation failures. */
final class McpException extends IOException {
  private final String code;
  private final String category;
  private final boolean retryable;

  private McpException(String code, String category, String message, boolean retryable) {
    super(message);
    this.code = code;
    this.category = category;
    this.retryable = retryable;
  }

  static McpException precondition(String code, String message, boolean retryable) {
    return new McpException(code, "PRECONDITION_FAILED", message, retryable);
  }

  static McpException conflict(String code, String message) {
    return new McpException(code, "CONFLICT", message, false);
  }

  static McpException validation(String code, String message) {
    return new McpException(code, "VALIDATION", message, false);
  }

  static McpException security(String code, String message) {
    return new McpException(code, "SECURITY", message, false);
  }

  String code() {
    return code;
  }

  String category() {
    return category;
  }

  boolean retryable() {
    return retryable;
  }
}
