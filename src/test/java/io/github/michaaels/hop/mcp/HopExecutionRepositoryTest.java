package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.json.schema.jackson3.JacksonJsonSchemaValidatorSupplier;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hop.core.row.RowBuffer;
import org.apache.hop.core.row.RowMeta;
import org.apache.hop.core.row.value.ValueMetaInteger;
import org.apache.hop.core.row.value.ValueMetaNumber;
import org.apache.hop.execution.Execution;
import org.apache.hop.execution.ExecutionData;
import org.apache.hop.execution.ExecutionDataSetMeta;
import org.apache.hop.execution.ExecutionState;
import org.apache.hop.execution.ExecutionStateComponentMetrics;
import org.apache.hop.execution.ExecutionType;
import org.apache.hop.execution.IExecutionInfoLocation;
import org.junit.jupiter.api.Test;

class HopExecutionRepositoryTest {
  @Test
  void readsBoundedNativeHistoryDetailChildrenAndMetrics() throws Exception {
    Path root = Files.createTempDirectory("hop-mcp-execution-repository").toRealPath();
    ProjectFiles files = new ProjectFiles(root);

    Execution execution =
        execution("root", root.resolve("pipelines/orders.hpl").toString(), null, 1000L);
    Execution child =
        execution("child", root.resolve("pipelines/orders.hpl").toString(), "root", 1100L);
    ExecutionState rootState = state("root", null, false, true, 1500L);
    rootState.setStatusDescription("Finished");
    rootState.setExecutionEndDate(new Date(1500L));
    rootState.setMetrics(
        List.of(
            new ExecutionStateComponentMetrics(
                "Table output", "0", Map.of("Rows read", 42L, "Errors", 0L))));
    ExecutionState childState = state("child", "root", true, false, 1200L);
    childState.setExecutionEndDate(new Date(1200L));

    Map<String, Execution> executions = Map.of("root", execution, "child", child);
    Map<String, ExecutionState> states = Map.of("root", rootState, "child", childState);
    IExecutionInfoLocation location = fakeLocation(executions, states);
    HopExecutionRepository repository =
        new HopExecutionRepository(files, null, null, (name, action) -> action.apply(location));

    Map<String, Object> history =
        repository.history("local", "orders", "finished", 900L, 1600L, 0, 10);
    assertEquals(1, history.get("count"));
    assertSchemaConforms("hop_execution_history", history);
    assertEquals(
        "pipelines/orders.hpl",
        ((Map<?, ?>) ((List<?>) history.get("executions")).get(0)).get("path"));

    Map<String, Object> detail = repository.detail("local", "root");
    assertSchemaConforms("hop_execution_detail", detail);
    assertEquals(true, detail.get("logging_available") == Boolean.FALSE);
    assertEquals(1, ((List<?>) detail.get("metrics")).size());
    assertEquals(
        42L,
        ((Map<?, ?>) ((Map<?, ?>) ((List<?>) detail.get("metrics")).get(0)).get("metrics"))
            .get("Rows read"));

    Map<String, Object> children = repository.children("local", "root", 2, 10);
    assertSchemaConforms("hop_execution_children", children);
    assertEquals(1, children.get("returned"));
    assertEquals(
        "child", ((Map<?, ?>) ((List<?>) children.get("children")).get(0)).get("execution_id"));

    Map<String, Object> metrics = repository.metrics("local", "root");
    assertEquals(true, metrics.get("available"));
    assertTrue(((List<?>) metrics.get("components")).size() == 1);

    RowMeta rowMeta = new RowMeta();
    rowMeta.addValueMeta(new ValueMetaInteger("CUSTOMER_ID"));
    rowMeta.addValueMeta(new ValueMetaNumber("TRAFFIC_MB"));
    ExecutionData data = new ExecutionData();
    data.setDataSets(
        Map.of(
            "rows",
            new RowBuffer(rowMeta, List.of(new Object[] {1L, 10.0}, new Object[] {null, 20.0}))));
    data.setSetMetaData(
        Map.of("rows", new ExecutionDataSetMeta("rows", "root", "Table Input", "0", "Rows")));
    Map<String, Object> profile =
        new HopExecutionRepository(
                files,
                null,
                null,
                (name, action) ->
                    action.apply(fakeLocation(executions, states, Map.of("root", data))))
            .profile("local", "root", "Table Input", List.of("CUSTOMER_ID", "TRAFFIC_MB"));
    Map<?, ?> profileFields = (Map<?, ?>) profile.get("fields");
    assertEquals(2L, profile.get("rows_scanned"));
    assertEquals(true, ((Map<?, ?>) profileFields.get("CUSTOMER_ID")).get("available"));
    assertEquals(true, ((Map<?, ?>) profileFields.get("CUSTOMER_ID")).get("complete"));
    assertEquals(1L, ((Map<?, ?>) profileFields.get("CUSTOMER_ID")).get("nulls"));
    assertEquals(
        15.0,
        ((Number) ((Map<?, ?>) profileFields.get("TRAFFIC_MB")).get("average")).doubleValue());
  }

  @Test
  void stopsHistoryScanAfterTheRequestedPageAndDefersStateReads() throws Exception {
    Path root = Files.createTempDirectory("hop-mcp-execution-page").toRealPath();
    Map<String, Execution> executions = new LinkedHashMap<>();
    Map<String, ExecutionState> states = new LinkedHashMap<>();
    for (int i = 0; i < 100; i++) {
      String id = "e" + i;
      String file =
          i < 2
              ? root.resolve("pipelines/target.hpl").toString()
              : root.resolve("pipelines/other.hpl").toString();
      executions.put(id, execution(id, file, null, 10_000L - i));
      states.put(id, state(id, null, false, false, 11_000L - i));
    }

    AtomicInteger executionReads = new AtomicInteger();
    AtomicInteger stateReads = new AtomicInteger();
    IExecutionInfoLocation location =
        (IExecutionInfoLocation)
            Proxy.newProxyInstance(
                IExecutionInfoLocation.class.getClassLoader(),
                new Class<?>[] {IExecutionInfoLocation.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("getExecutionIds")) {
                    return List.copyOf(executions.keySet());
                  }
                  if (method.getName().equals("getExecution")) {
                    executionReads.incrementAndGet();
                    return executions.get(String.valueOf(args[0]));
                  }
                  if (method.getName().equals("getExecutionState")) {
                    stateReads.incrementAndGet();
                    return states.get(String.valueOf(args[0]));
                  }
                  if (method.getReturnType() == boolean.class) return false;
                  if (method.getReturnType() == int.class) return 0;
                  if (method.getReturnType() == long.class) return 0L;
                  return null;
                });

    HopExecutionRepository repository =
        new HopExecutionRepository(
            new ProjectFiles(root), null, null, (name, action) -> action.apply(location));

    Map<String, Object> result = repository.history("local", "target.hpl", null, null, null, 0, 1);

    assertEquals(1, result.get("returned"));
    assertEquals(true, result.get("has_more"));
    assertEquals(2, executionReads.get());
    assertEquals(2, stateReads.get());
  }

  @Test
  void enforcesNativeExecutionBounds() throws Exception {
    Path root = Files.createTempDirectory("hop-mcp-execution-bounds").toRealPath();
    HopExecutionRepository repository =
        new HopExecutionRepository(
            new ProjectFiles(root),
            null,
            null,
            (name, action) -> action.apply(fakeLocation(Map.of(), Map.of())));

    assertThrows(
        IllegalArgumentException.class,
        () -> repository.history("local", null, null, 2L, 1L, 0, 10));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            repository.children("local", "id", HopExecutionRepository.MAX_CHILDREN_DEPTH + 1, 10));
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.history("", null, null, null, null, 0, 10));
  }

  @Test
  void preservesAmbiguousLegacyParentsEvenOnANewRuntime() throws Exception {
    Path root = Files.createTempDirectory("hop-mcp-legacy-parent").toRealPath();
    Execution legacy = execution("run", root.resolve("run.hpl").toString(), "old-launcher", 1000);
    HopExecutionRepository repository =
        new HopExecutionRepository(
            new ProjectFiles(root),
            null,
            null,
            (name, action) -> action.apply(fakeLocation(Map.of("run", legacy), Map.of())));
    Map<?, ?> row = (Map<?, ?>) repository.detail("local", "run").get("execution");
    assertEquals("old-launcher", row.get("parent_id"));
    assertEquals("", row.get("owner_execution_id"));
    assertEquals("", row.get("launched_by_id"));
    assertEquals("unresolved", row.get("parent_relationship"));
  }

  @Test
  void distinguishesOwnerAndLauncherAndTraversesCyclesOnce() throws Exception {
    Path root = Files.createTempDirectory("hop-mcp-owner-launcher").toRealPath();
    Execution owner = execution("owner", root.resolve("run.hwf").toString(), "grandchild", 1000);
    Execution launcher = execution("launcher", root.resolve("run.hwf").toString(), "owner", 1100);
    launcher.setExecutionType(ExecutionType.Action);
    Execution child = modernExecution("child", "owner", "launcher");
    Execution grandchild = modernExecution("grandchild", "child", "missing-launcher");
    Map<String, Execution> executions = new LinkedHashMap<>();
    for (Execution item : List.of(owner, launcher, child, grandchild))
      executions.put(item.getId(), item);
    HopExecutionRepository repository =
        new HopExecutionRepository(
            new ProjectFiles(root),
            null,
            null,
            (name, action) -> action.apply(fakeLocation(executions, Map.of())));

    Map<?, ?> detail = (Map<?, ?>) repository.detail("local", "child").get("execution");
    assertEquals("owner", detail.get("parent_id"));
    assertEquals("owner", detail.get("owner_execution_id"));
    assertEquals("launcher", detail.get("launched_by_id"));
    assertEquals("owner_and_launcher", detail.get("parent_relationship"));

    Map<String, Object> tree = repository.children("local", "owner", 2, 10);
    assertSchemaConforms("hop_execution_children", tree);
    assertEquals(3, tree.get("returned"));
    assertEquals(false, tree.get("truncated"));
    List<?> rows = (List<?>) tree.get("children");
    Map<?, ?> childRow =
        rows.stream()
            .map(Map.class::cast)
            .filter(row -> "child".equals(row.get("execution_id")))
            .findFirst()
            .orElseThrow();
    assertEquals(1, childRow.get("depth"));
    assertEquals("owner", childRow.get("via_execution_id"));
    assertEquals("parent", childRow.get("relationship"));

    Map<String, Object> launched = repository.children("local", "launcher", 1, 10);
    Map<?, ?> launchedRow = (Map<?, ?>) ((List<?>) launched.get("children")).getFirst();
    assertEquals("child", launchedRow.get("execution_id"));
    assertEquals("launcher", launchedRow.get("via_execution_id"));
    assertEquals("launcher", launchedRow.get("relationship"));
    assertEquals(true, launched.get("truncated"));
    assertEquals(true, repository.children("local", "owner", 1, 10).get("truncated"));
    assertEquals(true, repository.children("local", "owner", 5, 1).get("truncated"));
  }

  @Test
  void boundsChildDiscoveryAndReadsStateOnlyForReturnedRows() throws Exception {
    Path root = Files.createTempDirectory("hop-mcp-child-scan").toRealPath();
    AtomicInteger reads = new AtomicInteger();
    AtomicInteger stateReads = new AtomicInteger();
    List<String> ids =
        java.util.stream.IntStream.rangeClosed(0, HopExecutionRepository.MAX_HISTORY_SCAN)
            .mapToObj(i -> "child-" + i)
            .toList();
    IExecutionInfoLocation location =
        (IExecutionInfoLocation)
            Proxy.newProxyInstance(
                IExecutionInfoLocation.class.getClassLoader(),
                new Class<?>[] {IExecutionInfoLocation.class},
                (proxy, method, args) -> {
                  switch (method.getName()) {
                    case "getExecutionIds":
                      assertEquals(true, args[0]);
                      assertEquals(HopExecutionRepository.MAX_HISTORY_SCAN + 1, args[1]);
                      return ids;
                    case "getExecution":
                      reads.incrementAndGet();
                      String id = (String) args[0];
                      return execution(
                          id,
                          root.resolve("run.hpl").toString(),
                          id.equals("root") ? null : "root",
                          1000);
                    case "getExecutionState":
                      stateReads.incrementAndGet();
                      return null;
                    default:
                      throw new AssertionError(
                          "Unbounded or unexpected location call: " + method.getName());
                  }
                });
    HopExecutionRepository repository =
        new HopExecutionRepository(
            new ProjectFiles(root), null, null, (name, action) -> action.apply(location));
    Map<String, Object> result = repository.children("local", "root", 3, 1);
    assertEquals(1, result.get("returned"));
    assertEquals(true, result.get("truncated"));
    assertEquals(true, result.get("scan_truncated"));
    assertEquals(HopExecutionRepository.MAX_HISTORY_SCAN + 1, reads.get());
    assertEquals(1, stateReads.get());
  }

  @Test
  void aFailingLauncherApiDoesNotReturnAFalseLegacyRelationship() throws Exception {
    Execution broken = new BrokenLauncherExecution();
    broken.setId("broken");
    broken.setParentId("parent");
    McpException failure =
        assertThrows(McpException.class, () -> HopExecutionRelationships.read(broken));
    assertEquals("EXECUTION_RELATIONSHIP_UNAVAILABLE", failure.code());
    assertFalse(failure.getMessage().contains("native-secret"));
  }

  private static Execution modernExecution(String id, String owner, String launcher) {
    LauncherExecution execution = new LauncherExecution(launcher);
    execution.setId(id);
    execution.setParentId(owner);
    execution.setExecutionType(ExecutionType.Pipeline);
    return execution;
  }

  /** Supplies the additive getter when this test is compiled against Hop 2.19. */
  public static class LauncherExecution extends Execution {
    private final String launcher;

    LauncherExecution(String launcher) {
      this.launcher = launcher;
    }

    public String getLaunchedById() {
      return launcher;
    }
  }

  public static class BrokenLauncherExecution extends Execution {
    public String getLaunchedById() {
      throw new IllegalStateException("native-secret");
    }
  }

  private static void assertSchemaConforms(String tool, Map<String, Object> result) {
    var validation =
        new JacksonJsonSchemaValidatorSupplier()
            .get()
            .validate(HopMcpServer.outputSchemaFor(tool), result);
    assertTrue(validation.valid(), tool + ": " + validation.errorMessage());
  }

  private static Execution execution(String id, String filename, String parentId, long start) {
    Execution execution = new Execution();
    execution.setId(id);
    execution.setFilename(filename);
    execution.setName("orders");
    execution.setParentId(parentId);
    execution.setExecutionType(ExecutionType.Pipeline);
    execution.setRunConfigurationName("local");
    execution.setRegistrationDate(new Date(start - 10L));
    execution.setExecutionStartDate(new Date(start));
    return execution;
  }

  private static ExecutionState state(
      String id, String parentId, boolean failed, boolean running, long updateTime) {
    ExecutionState state = new ExecutionState();
    state.setId(id);
    state.setParentId(parentId);
    state.setName("orders");
    state.setExecutionType(ExecutionType.Pipeline);
    state.setFailed(failed);
    state.setStatusDescription(running ? "Running" : "Finished");
    state.setUpdateTime(new Date(updateTime));
    return state;
  }

  private static IExecutionInfoLocation fakeLocation(
      Map<String, Execution> executions, Map<String, ExecutionState> states) {
    return fakeLocation(executions, states, Map.of());
  }

  private static IExecutionInfoLocation fakeLocation(
      Map<String, Execution> executions,
      Map<String, ExecutionState> states,
      Map<String, ExecutionData> data) {
    return (IExecutionInfoLocation)
        Proxy.newProxyInstance(
            IExecutionInfoLocation.class.getClassLoader(),
            new Class<?>[] {IExecutionInfoLocation.class},
            (proxy, method, args) -> {
              String name = method.getName();
              if (name.equals("getExecutionIds")) return List.copyOf(executions.keySet());
              if (name.equals("getExecution")) return executions.get(String.valueOf(args[0]));
              if (name.equals("getExecutionState")) return states.get(String.valueOf(args[0]));
              if (name.equals("getExecutionData")) return data.get(String.valueOf(args[0]));
              if (name.equals("findExecutions")) {
                String parent = String.valueOf(args[0]);
                return executions.values().stream()
                    .filter(execution -> parent.equals(execution.getParentId()))
                    .toList();
              }
              if (name.equals("getPluginId")) return "fake";
              if (name.equals("getPluginName")) return "fake";
              if (name.equals("clone")) return proxy;
              if (method.getReturnType() == boolean.class) return false;
              if (method.getReturnType() == int.class) return 0;
              if (method.getReturnType() == long.class) return 0L;
              return null;
            });
  }
}
