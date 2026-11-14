package io.github.michaaels.hop.mcp;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;

/** Bounded look at standard initialize.clientInfo; the SDK still validates and handles the RPC. */
final class HopMcpInitialRequest extends HttpServletRequestWrapper {
  private final byte[] body;

  HopMcpInitialRequest(HttpServletRequest request) throws IOException {
    super(request);
    body = request.getInputStream().readNBytes(HopRemoteMcpConnection.MAX_REQUEST_BYTES + 1);
  }

  boolean oversized() {
    return body.length > HopRemoteMcpConnection.MAX_REQUEST_BYTES;
  }

  Map<String, String> identity() {
    try {
      var envelope = new JacksonMcpJsonMapperSupplier().get().readValue(body, Map.class);
      if (!"initialize".equals(envelope.get("method"))
          || !(envelope.get("params") instanceof Map<?, ?> params)
          || !(params.get("clientInfo") instanceof Map<?, ?> info)) return Map.of();
      return Map.of(
          "name",
          info.get("name") instanceof String value ? value : "No informado",
          "version",
          info.get("version") instanceof String value ? value : "");
    } catch (Exception invalid) {
      return Map.of();
    }
  }

  @Override
  public ServletInputStream getInputStream() {
    var bytes = new ByteArrayInputStream(body);
    return new ServletInputStream() {
      @Override
      public int read() {
        return bytes.read();
      }

      @Override
      public int read(byte[] target, int offset, int length) {
        return bytes.read(target, offset, length);
      }

      @Override
      public boolean isFinished() {
        return bytes.available() == 0;
      }

      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setReadListener(ReadListener listener) {
        throw new IllegalStateException("The RAP MCP endpoint is synchronous");
      }
    };
  }

  @Override
  public java.io.BufferedReader getReader() {
    return new java.io.BufferedReader(
        new java.io.InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
  }
}
