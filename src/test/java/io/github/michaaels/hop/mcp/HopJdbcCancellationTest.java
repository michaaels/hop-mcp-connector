package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.database.DatabaseMeta;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.pipeline.PipelineHopMeta;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.config.PipelineRunConfiguration;
import org.apache.hop.pipeline.engines.local.LocalPipelineRunConfiguration;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Cancels SQL that has entered a real in-memory JDBC engine, without external infrastructure. */
@EnabledIfSystemProperty(named = "hop.compatibility.components", matches = "true")
@Timeout(20)
public class HopJdbcCancellationTest {
  private static volatile CountDownLatch queryStarted;
  private static final AtomicReference<Thread> QUERY_THREAD = new AtomicReference<>();

  @TempDir Path project;

  @BeforeAll
  static void initializePlugins() throws Exception {
    HopEnvironment.init();
  }

  /** Called by H2 inside the running aggregation, so cancellation cannot race query startup. */
  public static long signalQuery(long value) {
    QUERY_THREAD.compareAndSet(null, Thread.currentThread());
    queryStarted.countDown();
    return value;
  }

  @Test
  void cancelsAnExecutingTableInputQueryThroughTheMcpService() throws Exception {
    cancelQuery("TableInput", false);
  }

  @Test
  void cancelsAnExecutingDatabaseJoinQueryThroughTheMcpService() throws Exception {
    cancelQuery("DBJoin", false);
  }

  @Test
  void timesOutAnExecutingTableInputQueryAndRemainsUsable() throws Exception {
    cancelQuery("TableInput", true);
  }

  @Test
  void timesOutAnExecutingDatabaseJoinQueryAndRemainsUsable() throws Exception {
    cancelQuery("DBJoin", true);
  }

  private void cancelQuery(String pluginId, boolean timeout) throws Exception {
    queryStarted = new CountDownLatch(1);
    QUERY_THREAD.set(null);
    String databaseName = "mcp_cancel_" + UUID.randomUUID().toString().replace("-", "");
    String databasePath = "mem:" + databaseName + ";DB_CLOSE_DELAY=-1";
    try (Connection admin = DriverManager.getConnection("jdbc:h2:" + databasePath, "sa", "");
        Statement statement = admin.createStatement()) {
      statement.execute(
          "CREATE ALIAS SIGNAL_QUERY FOR '"
              + HopJdbcCancellationTest.class.getName()
              + ".signalQuery'");
      Variables variables = new Variables();
      MemoryMetadataProvider provider = new MemoryMetadataProvider();
      DatabaseMeta connection =
          new DatabaseMeta("Cancellation", "H2", "Native", "", databasePath, "", "sa", "");
      provider.getSerializer(DatabaseMeta.class).save(connection);
      PipelineRunConfiguration run = new PipelineRunConfiguration();
      run.setName("local");
      LocalPipelineRunConfiguration engine = new LocalPipelineRunConfiguration();
      engine.setEnginePluginId("Local");
      run.setEngineRunConfiguration(engine);
      provider.getSerializer(PipelineRunConfiguration.class).save(run);
      HopComponentAuthoring authoring =
          new HopComponentAuthoring(PluginRegistry.getInstance(), provider);
      TransformMeta query =
          authoring.createTransform(
              pluginId,
              "Query",
              Map.of(),
              pluginId.equals("DBJoin")
                  ? Map.of("parameter", List.of(Map.of("NAME", "trigger", "TYPE", "Integer")))
                  : Map.of(),
              200,
              100);
      Object queryMeta = query.getTransform();
      queryMeta
          .getClass()
          .getMethod("setConnection", String.class)
          .invoke(queryMeta, connection.getName());
      queryMeta
          .getClass()
          .getMethod("setSql", String.class)
          .invoke(
              queryMeta,
              // Metadata discovery binds NULL: the empty range is cheap. Only the real input row
              // starts a long query, avoiding an unrelated metadata-query cancellation path.
              "SELECT SUM(SIGNAL_QUERY(X)) AS TOTAL FROM SYSTEM_RANGE(1, COALESCE(CAST(? AS BIGINT), 0))");
      PipelineMeta pipeline = new PipelineMeta();
      pipeline.setName("JDBC cancellation fixture");
      pipeline.addTransform(query);
      {
        TransformMeta input =
            authoring.createTransform(
                "RowGenerator",
                "One row",
                Map.of("limit", "1"),
                Map.of(
                    "fields",
                    List.of(Map.of("name", "trigger", "type", "Integer", "nullif", "1000000000"))),
                100,
                100);
        pipeline.addTransform(input);
        pipeline.addPipelineHop(new PipelineHopMeta(input, query));
        if (pluginId.equals("TableInput")) {
          queryMeta.getClass().getMethod("setLookup", String.class).invoke(queryMeta, "One row");
          queryMeta
              .getClass()
              .getMethod("setExecuteEachInputRow", boolean.class)
              .invoke(queryMeta, true);
        }
      }
      Files.writeString(project.resolve("query.hpl"), pipeline.getXml(variables));
      try (HopMcpService service =
          new HopMcpService(
              new ProjectFiles(project.toRealPath()),
              variables,
              provider,
              false,
              true,
              false,
              false,
              null)) {
        String id =
            (String)
                service
                    .startExecution("query.hpl", "local", Map.of(), timeout ? 3 : 30)
                    .get("operation_id");
        try {
          assertTrue(
              queryStarted.await(8, TimeUnit.SECONDS),
              () -> "Query did not start: " + service.executionStatus(id));
          if (!timeout) service.stopExecution(id);
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
          Map<String, Object> status = service.executionStatus(id);
          while ((status.get("state").equals("running") || status.get("state").equals("stopping"))
              && System.nanoTime() < deadline) {
            Thread.sleep(10);
            status = service.executionStatus(id);
          }
          assertEquals(timeout ? "timed_out" : "stopped", status.get("state"), status.toString());
          assertNotNull(QUERY_THREAD.get());
          try (PreparedStatement activeQuery =
              admin.prepareStatement(
                  "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE EXECUTING_STATEMENT LIKE 'SELECT SUM(SIGNAL_QUERY%'")) {
            long queryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            int remaining;
            do {
              try (var rows = activeQuery.executeQuery()) {
                rows.next();
                remaining = rows.getInt(1);
              }
              if (remaining > 0) Thread.sleep(10);
            } while (remaining > 0 && System.nanoTime() < queryDeadline);
            assertEquals(
                0,
                remaining,
                () ->
                    "JDBC query survived cancellation on "
                        + QUERY_THREAD.get().getName()
                        + ": "
                        + java.util.Arrays.toString(QUERY_THREAD.get().getStackTrace()));
          }
          assertEquals(0, service.executionStatus(id).get("active_executions"));
          assertEquals(true, service.validate("query.hpl").get("valid"));
          queryMeta
              .getClass()
              .getMethod("setSql", String.class)
              .invoke(queryMeta, "SELECT ? AS TOTAL");
          Files.writeString(project.resolve("query.hpl"), pipeline.getXml(variables));
          assertEquals(true, service.execute("query.hpl", "local", Map.of(), 5).get("ok"));
        } finally {
          service.stopExecution(id);
        }
      } finally {
        // H2 closes this isolated database even when an assertion failed during the query.
        statement.execute("SHUTDOWN IMMEDIATELY");
      }
    }
  }
}
