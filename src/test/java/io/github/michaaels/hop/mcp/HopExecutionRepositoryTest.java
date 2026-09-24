package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import org.apache.hop.execution.Execution;
import org.apache.hop.execution.ExecutionData;
import org.apache.hop.execution.ExecutionDataSetMeta;
import org.apache.hop.execution.ExecutionState;
import org.apache.hop.execution.ExecutionStateComponentMetrics;
import org.apache.hop.execution.ExecutionType;
import org.apache.hop.execution.IExecutionInfoLocation;
import org.apache.hop.core.row.RowBuffer;
import org.apache.hop.core.row.RowMeta;
import org.apache.hop.core.row.value.ValueMetaInteger;
import org.apache.hop.core.row.value.ValueMetaNumber;
import org.junit.jupiter.api.Test;

class HopExecutionRepositoryTest {
  @Test
  void readsBoundedNativeHistoryDetailChildrenAndMetrics() throws Exception {
    Path root = Files.createTempDirectory("hop-mcp-execution-repository");
    ProjectFiles files = new ProjectFiles(root);

    Execution execution = execution("root", root.resolve("pipelines/orders.hpl").toString(), null, 1000L);
    Execution child = execution("child", root.resolve("pipelines/orders.hpl").toString(), "root", 1100L);
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
        new HopExecutionRepository(
            files,
            null,
            null,
            (name, action) -> action.apply(location));

    Map<String, Object> history = repository.history("local", "orders", "finished", 900L, 1600L, 0, 10);
    assertEquals(1, history.get("count"));
    assertEquals("pipelines/orders.hpl", ((Map<?, ?>) ((List<?>) history.get("executions")).get(0)).get("path"));

    Map<String, Object> detail = repository.detail("local", "root");
    assertEquals(true, detail.get("logging_available") == Boolean.FALSE);
    assertEquals(1, ((List<?>) detail.get("metrics")).size());
    assertEquals(42L, ((Map<?, ?>) ((Map<?, ?>) ((List<?>) detail.get("metrics")).get(0)).get("metrics")).get("Rows read"));

    Map<String, Object> children = repository.children("local", "root", 2, 10);
    assertEquals(1, children.get("returned"));
    assertEquals("child", ((Map<?, ?>) ((List<?>) children.get("children")).get(0)).get("execution_id"));

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
            new RowBuffer(
                rowMeta,
                List.of(
                    new Object[] {1L, 10.0},
                    new Object[] {null, 20.0}))));
    data.setSetMetaData(
        Map.of("rows", new ExecutionDataSetMeta("rows", "root", "Table Input", "0", "Rows")));
    Map<String, Object> profile =
        new HopExecutionRepository(
                files,
                null,
                null,
                (name, action) -> action.apply(fakeLocation(executions, states, Map.of("root", data))))
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
  void enforcesNativeExecutionBounds() throws Exception {
    Path root = Files.createTempDirectory("hop-mcp-execution-bounds");
    HopExecutionRepository repository =
        new HopExecutionRepository(
            new ProjectFiles(root), null, null, (name, action) -> action.apply(fakeLocation(Map.of(), Map.of())));

    assertThrows(
        IllegalArgumentException.class,
        () -> repository.history("local", null, null, 2L, 1L, 0, 10));
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.children("local", "id", HopExecutionRepository.MAX_CHILDREN_DEPTH + 1, 10));
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.history("", null, null, null, null, 0, 10));
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
