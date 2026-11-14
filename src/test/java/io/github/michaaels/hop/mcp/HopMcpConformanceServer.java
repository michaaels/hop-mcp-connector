package io.github.michaaels.hop.mcp;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;

/** Test-only Streamable HTTP adapter for the official MCP conformance runner. */
public final class HopMcpConformanceServer {
  private HopMcpConformanceServer() {}

  public static void main(String[] args) throws Exception {
    Path projectRoot = requiredPath(args, "--root").toAbsolutePath().normalize();
    Path readyFile = requiredPath(args, "--ready-file").toAbsolutePath().normalize();
    int requestedPort = integerOption(args, "--port", 0);
    Files.createDirectories(projectRoot);
    Files.createDirectories(readyFile.getParent());

    HopEnvironment.init();
    HopMcpService service =
        new HopMcpService(
            new ProjectFiles(projectRoot), new Variables(), new MemoryMetadataProvider(), false);
    var mapper = new JacksonMcpJsonMapperSupplier().get();
    var transportProvider =
        HttpServletStreamableServerTransportProvider.builder()
            .jsonMapper(mapper)
            .mcpEndpoint("/mcp")
            .keepAliveInterval(Duration.ofSeconds(30))
            .securityValidator(
                DefaultServerTransportSecurityValidator.builder()
                    .allowedHost("127.0.0.1:*")
                    .allowedOrigin("http://127.0.0.1:*")
                    .build())
            .build();

    try (HopMcpServer server =
        HopMcpServer.withSpecificationFactory(
            service, (ignoredMapper, ignoredIn, ignoredOut) -> McpServer.sync(transportProvider))) {
      Tomcat tomcat = createTomcat(transportProvider, requestedPort);
      CountDownLatch stop = new CountDownLatch(1);
      Runtime.getRuntime().addShutdownHook(new Thread(stop::countDown, "hop-mcp-conformance-stop"));
      try {
        tomcat.start();
        int port = tomcat.getConnector().getLocalPort();
        Files.writeString(readyFile, "http://127.0.0.1:" + port + "/mcp\n");
        System.err.println("MCP conformance test adapter listening on 127.0.0.1:" + port + "/mcp");
        stop.await();
      } finally {
        stop.countDown();
        stopTomcat(tomcat);
      }
    }
  }

  static Tomcat createTomcat(jakarta.servlet.http.HttpServlet transportProvider, int port)
      throws IOException {
    Tomcat tomcat = new Tomcat();
    tomcat.setPort(port);
    tomcat.setBaseDir(Files.createTempDirectory("hop-mcp-conformance-tomcat-").toString());
    Context context = tomcat.addContext("", tomcat.getServer().getCatalinaBase().getAbsolutePath());
    var wrapper = context.createWrapper();
    wrapper.setName("mcpServlet");
    wrapper.setServlet(transportProvider);
    wrapper.setLoadOnStartup(1);
    wrapper.setAsyncSupported(true);
    context.addChild(wrapper);
    context.addServletMappingDecoded("/*", "mcpServlet");
    // An idle SSE GET must deliver its headers before the first event. Otherwise clients
    // cannot start their stream-read timeout or cancel a resumability probe with no events.
    FilterDef headers = new FilterDef();
    headers.setFilterName("mcpGetHeaders");
    headers.setAsyncSupported("true");
    headers.setFilter(
        (request, response, chain) -> {
          chain.doFilter(request, response);
          if (request instanceof HttpServletRequest httpRequest
              && "GET".equals(httpRequest.getMethod())
              && request.isAsyncStarted()
              && response instanceof HttpServletResponse httpResponse
              && httpResponse.getContentType() != null
              && httpResponse.getContentType().startsWith("text/event-stream")) {
            httpResponse.flushBuffer();
          }
        });
    context.addFilterDef(headers);
    FilterMap mapping = new FilterMap();
    mapping.setFilterName(headers.getFilterName());
    mapping.addURLPattern("/*");
    context.addFilterMap(mapping);
    tomcat.getConnector().setProperty("address", "127.0.0.1");
    tomcat.getConnector().setAsyncTimeout(30_000);
    return tomcat;
  }

  private static void stopTomcat(Tomcat tomcat) {
    try {
      tomcat.stop();
      tomcat.destroy();
    } catch (LifecycleException e) {
      System.err.println("Unable to stop MCP conformance test adapter: " + e.getMessage());
    }
  }

  private static Path requiredPath(String[] args, String name) {
    String value = option(args, name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Missing " + name);
    }
    return Path.of(value);
  }

  private static int integerOption(String[] args, String name, int defaultValue) {
    String value = option(args, name);
    return value == null ? defaultValue : Integer.parseInt(value);
  }

  private static String option(String[] args, String name) {
    for (int i = 0; i + 1 < args.length; i++) {
      if (name.equals(args[i])) return args[i + 1];
    }
    return null;
  }
}
