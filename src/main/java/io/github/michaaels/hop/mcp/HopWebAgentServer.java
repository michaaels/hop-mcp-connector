package io.github.michaaels.hop.mcp;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.json.schema.jackson3.JacksonJsonSchemaValidatorSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/** Four small agent-facing tools; native discovery and operations stay on the Web server. */
final class HopWebAgentServer implements AutoCloseable {
  @FunctionalInterface
  private interface Handler {
    McpSchema.CallToolResult call(Map<String, Object> args) throws Exception;
  }

  private final HopWebAgentClient client;
  private final McpSyncServer server;
  private final CountDownLatch eof = new CountDownLatch(1);

  HopWebAgentServer(InputStream input, OutputStream output, Path additionalCertificate)
      throws Exception {
    client = new HopWebAgentClient(additionalCertificate);
    var tracking =
        new FilterInputStream(input) {
          @Override
          public int read() throws IOException {
            int value = super.read();
            if (value < 0) eof.countDown();
            return value;
          }

          @Override
          public int read(byte[] b, int offset, int length) throws IOException {
            int count = super.read(b, offset, length);
            if (count < 0) eof.countDown();
            return count;
          }

          @Override
          public void close() throws IOException {
            try {
              super.close();
            } finally {
              eof.countDown();
            }
          }
        };
    var mapper = new JacksonMcpJsonMapperSupplier().get();
    server =
        McpServer.sync(new StdioServerTransportProvider(mapper, tracking, output))
            .jsonMapper(mapper)
            .jsonSchemaValidator(new JacksonJsonSchemaValidatorSupplier().get())
            .serverInfo("hop-web-agent", HopMcpVersion.current())
            .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
            .instructions(
                "When the user gives a Hop Web UI URL, call hop_web_connect with that URL. It resolves the MCP endpoint automatically. If state=ambiguous, show the sessions with their project and open file, ask the user which session to use, then call hop_web_connect with that URL and the chosen session_id. Never guess a session or select one automatically from an ambiguous list. A disconnected or inactive state must be reported without using a local project. Discover only the native tools needed with hop_web_tools, then invoke them through hop_web_call. Before editing inspect hop_config and the target definition. Use native correction plans with presentation=guided and small appended blocks, or instant when requested. Preserve SHA-256, protected backups and rollback. No ETL execution is exposed by the Web connection."
                    + HopAuthoringGuidance.INSTRUCTIONS)
            .tools(
                List.of(
                    tool(
                        "hop_web_connect",
                        "Connect to Hop Web by URL. With multiple sessions returns project/file choices; ask the user and supply their chosen session_id. Never uses a project share.",
                        schema(
                            Map.of(
                                "url",
                                Map.of("type", "string", "maxLength", 2048),
                                "session_id",
                                Map.of(
                                    "type",
                                    "string",
                                    "maxLength",
                                    36,
                                    "pattern",
                                    "^[0-9a-fA-F-]{36}$")),
                            List.of("url")),
                        true,
                        args ->
                            result(
                                client.connect(
                                    String.valueOf(args.get("url")),
                                    (String) args.get("session_id")))),
                    tool(
                        "hop_web_status",
                        "Check whether the selected Hop Web session is still connected and MCP Live Sync remains active.",
                        schema(Map.of(), List.of()),
                        true,
                        args -> result(client.status())),
                    tool(
                        "hop_web_tools",
                        "Discover a bounded page of native tools available on the connected Hop Web, including their input schemas. Filter names to avoid loading a large catalog.",
                        schema(
                            Map.of(
                                "query",
                                Map.of("type", "string", "maxLength", 128),
                                "offset",
                                Map.of("type", "integer", "minimum", 0, "maximum", 200),
                                "limit",
                                Map.of("type", "integer", "minimum", 1, "maximum", 20)),
                            List.of()),
                        true,
                        args ->
                            result(
                                client.tools(
                                    (String) args.get("query"),
                                    number(args, "offset", 0),
                                    number(args, "limit", 10)))),
                    tool(
                        "hop_web_call",
                        "Call a discovered native Hop Web tool with its documented arguments. Operates only on the connected Web project; authoring preserves native plans, preview, SHA-256, backups and rollback.",
                        schema(
                            Map.of(
                                "name",
                                Map.of("type", "string", "maxLength", 128),
                                "arguments",
                                Map.of(
                                    "type",
                                    "object",
                                    "maxProperties",
                                    50,
                                    "additionalProperties",
                                    true)),
                            List.of("name", "arguments")),
                        false,
                        args ->
                            client.call(
                                (String) args.get("name"), arguments(args.get("arguments"))))))
            .build();
  }

  private McpServerFeatures.SyncToolSpecification tool(
      String name,
      String description,
      Map<String, Object> schema,
      boolean readOnly,
      Handler handler) {
    var definition =
        McpSchema.Tool.builder(name, schema)
            .description(description)
            .outputSchema(Map.of("type", "object", "additionalProperties", true))
            .annotations(
                new McpSchema.ToolAnnotations(null, readOnly, !readOnly, readOnly, true, false))
            .build();
    return McpServerFeatures.SyncToolSpecification.builder()
        .tool(definition)
        .callHandler(
            (exchange, request) -> {
              try {
                if (exchange != null) client.agentInfo(exchange.getClientInfo());
                return handler.call(request.arguments() == null ? Map.of() : request.arguments());
              } catch (Exception failure) {
                var error =
                    SensitiveData.redactMap(
                        Map.of(
                            "code",
                            "HOP_WEB_REQUEST_FAILED",
                            "message",
                            failure.getMessage() == null
                                ? "No se pudo completar la operación en Hop Web"
                                : failure.getMessage()));
                return McpSchema.CallToolResult.builder()
                    .structuredContent(error)
                    .content(List.of(new McpSchema.TextContent(JsonUtil.toJson(error))))
                    .isError(true)
                    .build();
              }
            })
        .build();
  }

  private static McpSchema.CallToolResult result(Map<String, Object> data) {
    var safe = SensitiveData.redactMap(data);
    String json = JsonUtil.toJson(safe);
    String wire =
        JsonUtil.toJson(
            Map.of(
                "content",
                List.of(Map.of("type", "text", "text", json)),
                "structuredContent",
                safe,
                "isError",
                false));
    if (wire.getBytes(StandardCharsets.UTF_8).length + 128 > 512 * 1024) {
      throw new IllegalArgumentException("Request a smaller page of remote tools");
    }
    return McpSchema.CallToolResult.builder()
        .structuredContent(safe)
        .content(List.of(new McpSchema.TextContent(json)))
        .isError(false)
        .build();
  }

  private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
    return Map.of(
        "type",
        "object",
        "properties",
        properties,
        "required",
        required,
        "additionalProperties",
        false);
  }

  private static int number(Map<String, Object> args, String key, int fallback) {
    return args.get(key) instanceof Number value ? value.intValue() : fallback;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> arguments(Object value) {
    return (Map<String, Object>) value;
  }

  void awaitEof() throws InterruptedException {
    eof.await();
  }

  @Override
  public void close() {
    try {
      server.closeGracefully();
    } finally {
      client.close();
    }
  }
}
