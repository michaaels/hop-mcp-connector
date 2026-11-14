package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.hop.core.Const;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.logging.HopLogStore;
import org.apache.hop.core.logging.LogChannel;
import org.apache.hop.core.variables.Variables;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class HopMcpCommandTest {
  @TempDir Path project;

  @BeforeAll
  static void initializeHopPlugins() throws Exception {
    HopEnvironment.init();
  }

  @Test
  @Timeout(15)
  void lateWorkerLogsStayOnStderrAfterStdioEof() throws Exception {
    assertLateLogsAreIsolated(false);
  }

  @Test
  @Timeout(15)
  void lateWorkerLogsStayOnStderrAfterStartupFailure() throws Exception {
    assertLateLogsAreIsolated(true);
  }

  private void assertLateLogsAreIsolated(boolean startupFailure) throws Exception {
    HopMcpCommand command = new HopMcpCommand();
    CommandLine cli = new CommandLine(command);
    command.initialize(cli, new Variables(), null);
    Path root =
        startupFailure ? Files.writeString(project.resolve("not-a-directory"), "x") : project;
    cli.parseArgs("--root", root.toString());

    InputStream previousIn = System.in;
    PrintStream previousOut = System.out;
    PrintStream previousErr = System.err;
    PrintStream previousHopOut = HopLogStore.OriginalSystemOut;
    PrintStream previousHopErr = HopLogStore.OriginalSystemErr;
    String previousRuntime = System.getProperty(Const.HOP_PLATFORM_RUNTIME);
    ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
    ByteArrayOutputStream protocol = new ByteArrayOutputStream();
    ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
    try (PrintStream protocolOut = new PrintStream(protocol, true, StandardCharsets.UTF_8);
        PrintStream hopOut = new PrintStream(protocol, true, StandardCharsets.UTF_8);
        PrintStream stderr = new PrintStream(diagnostics, true, StandardCharsets.UTF_8)) {
      System.setIn(new ByteArrayInputStream(new byte[0]));
      System.setOut(protocolOut);
      System.setErr(stderr);
      HopLogStore.OriginalSystemOut = hopOut;
      HopLogStore.OriginalSystemErr = stderr;

      if (startupFailure) assertThrows(RuntimeException.class, command::run);
      else command.run();
      assertSame(previousLoader, Thread.currentThread().getContextClassLoader());

      // Force the ordering that timed-out native pipelines can produce: the command
      // has returned, but a worker still emits its final log and console output.
      Thread lateWorker =
          new Thread(
              () -> {
                new LogChannel("late-native-worker").logBasic("Pipeline duration after timeout");
                System.out.println("late-plugin-console-output");
              },
              "late-hop-worker");
      lateWorker.setDaemon(true);
      lateWorker.start();
      lateWorker.join(5000);
      assertFalse(lateWorker.isAlive(), "Late logging must not block");
      assertEquals("", protocol.toString(StandardCharsets.UTF_8));
      String logs = diagnostics.toString(StandardCharsets.UTF_8);
      assertTrue(logs.contains("Pipeline duration after timeout"), logs);
      assertTrue(logs.contains("late-plugin-console-output"), logs);
    } finally {
      // Only the test harness restores process streams. The CLI owns them until exit.
      System.setIn(previousIn);
      System.setOut(previousOut);
      System.setErr(previousErr);
      HopLogStore.OriginalSystemOut = previousHopOut;
      HopLogStore.OriginalSystemErr = previousHopErr;
      if (previousRuntime == null) System.clearProperty(Const.HOP_PLATFORM_RUNTIME);
      else System.setProperty(Const.HOP_PLATFORM_RUNTIME, previousRuntime);
    }
  }

  @Test
  @Timeout(25)
  void cachedSdkConsoleAppenderCannotCorruptProtocolOnInvalidArguments() throws Exception {
    String loggerName = "io.modelcontextprotocol.util.ToolInputValidator";
    LoggerContext context = (LoggerContext) LogManager.getContext(false);
    var configuration = context.getConfiguration();
    var previousLogger = configuration.getLoggers().get(loggerName);
    InputStream previousIn = System.in;
    PrintStream previousOut = System.out;
    PrintStream previousErr = System.err;
    PrintStream previousHopOut = HopLogStore.OriginalSystemOut;
    PrintStream previousHopErr = HopLogStore.OriginalSystemErr;
    String previousRuntime = System.getProperty(Const.HOP_PLATFORM_RUNTIME);
    var executor = Executors.newSingleThreadExecutor();
    ByteArrayOutputStream protocol = new ByteArrayOutputStream();
    ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
    try (PrintStream stdout = new PrintStream(protocol, true, StandardCharsets.UTF_8);
        PrintStream stderr = new PrintStream(diagnostics, true, StandardCharsets.UTF_8);
        PipedInputStream input = new PipedInputStream(65536);
        PipedOutputStream requestOutput = new PipedOutputStream(input);
        PrintWriter requests = new PrintWriter(requestOutput, true, StandardCharsets.UTF_8)) {
      System.setOut(stdout);
      System.setErr(stderr);
      System.setIn(input);
      HopLogStore.OriginalSystemOut = stdout;
      HopLogStore.OriginalSystemErr = stderr;
      var cachedAppender =
          ConsoleAppender.newBuilder()
              .setName("cached-sdk-stdout")
              .setTarget(ConsoleAppender.Target.SYSTEM_OUT)
              .setLayout(PatternLayout.newBuilder().withPattern("%m%n").build())
              .build();
      cachedAppender.start();
      configuration.addAppender(cachedAppender);
      var sdkLogger = new LoggerConfig(loggerName, Level.WARN, false);
      sdkLogger.addAppender(cachedAppender, Level.WARN, null);
      configuration.addLogger(loggerName, sdkLogger);
      context.updateLoggers();
      LogManager.getLogger(loggerName).warn("cached-before-command");
      assertTrue(protocol.toString(StandardCharsets.UTF_8).contains("cached-before-command"));
      protocol.reset();

      HopMcpCommand command = new HopMcpCommand();
      CommandLine cli = new CommandLine(command);
      command.initialize(cli, new Variables(), null);
      cli.parseArgs("--root", project.toString());
      var running = executor.submit(command::run);
      requests.println(
          "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"console-regression\",\"version\":\"1\"}}}");
      requests.println("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
      requests.println(
          "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"hop_context\",\"arguments\":{}}}");
      requests.println(
          "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"hop_config\",\"arguments\":{}}}");
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
      while (!protocol.toString(StandardCharsets.UTF_8).contains("\"id\":3")
          && System.nanoTime() < deadline) Thread.sleep(10);
      // The Maven runtime may bind SLF4J differently from the installed Hop client.
      // Exercise the already-cached SDK-named Log4j appender deterministically as well.
      LogManager.getLogger(loggerName).warn("cached-after-command");
      requestOutput.close();
      running.get(5, TimeUnit.SECONDS);
      var mapper = new JacksonMcpJsonMapperSupplier().get();
      var responses = protocol.toString(StandardCharsets.UTF_8).lines().toList();
      assertEquals(3, responses.size(), protocol.toString(StandardCharsets.UTF_8));
      for (String line : responses)
        assertEquals("2.0", mapper.readValue(line, Map.class).get("jsonrpc"));
      assertTrue(protocol.toString(StandardCharsets.UTF_8).contains("\"id\":3"));
      assertTrue(
          diagnostics.toString(StandardCharsets.UTF_8).contains("cached-after-command"),
          diagnostics.toString(StandardCharsets.UTF_8));
      var invalid =
          responses.stream()
              .map(
                  line -> {
                    try {
                      return mapper.readValue(line, Map.class);
                    } catch (Exception e) {
                      throw new AssertionError(e);
                    }
                  })
              .filter(response -> Integer.valueOf(2).equals(response.get("id")))
              .findFirst()
              .orElseThrow();
      assertTrue(
          invalid.containsKey("error")
              || Boolean.TRUE.equals(((Map<?, ?>) invalid.get("result")).get("isError")));
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(2, TimeUnit.SECONDS);
      configuration.removeLogger(loggerName);
      if (previousLogger != null) configuration.addLogger(loggerName, previousLogger);
      context.updateLoggers();
      System.setIn(previousIn);
      System.setOut(previousOut);
      System.setErr(previousErr);
      HopLogStore.OriginalSystemOut = previousHopOut;
      HopLogStore.OriginalSystemErr = previousHopErr;
      if (previousRuntime == null) System.clearProperty(Const.HOP_PLATFORM_RUNTIME);
      else System.setProperty(Const.HOP_PLATFORM_RUNTIME, previousRuntime);
    }
  }
}
