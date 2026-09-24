package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.execution.Execution;
import org.apache.hop.execution.ExecutionState;
import org.apache.hop.execution.ExecutionType;
import org.apache.hop.execution.IExecutionInfoLocation;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.config.PipelineRunConfiguration;
import org.apache.hop.pipeline.engines.local.LocalPipelineRunConfiguration;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.apache.hop.pipeline.transforms.dummy.DummyMeta;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HopDiagnosisServiceTest {
  @TempDir Path project;

  @BeforeAll
  static void initializeHopPlugins() throws Exception {
    HopEnvironment.init();
  }

  @Test
  void aggregatesEvidenceAndLabelsPossibleCausesAsUnverified() throws Exception {
    Variables variables = new Variables();
    MemoryMetadataProvider metadataProvider = new MemoryMetadataProvider();
    saveLocalConfiguration(metadataProvider);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setNameSynchronizedWithFilename(false);
    pipeline.setName("Orders");
    pipeline.addTransform(new TransformMeta("Dummy", "Input", new DummyMeta()));
    String xml = pipeline.getXml(variables);
    int closing = xml.lastIndexOf("</");
    Files.writeString(
        project.resolve("orders.hpl"),
        xml.substring(0, closing)
            + "<environment><connection>MISSING_CONN</connection><metadata>ORDERS_META</metadata></environment>"
            + "<parameters><parameter><name>THREADS</name><default_value>4</default_value></parameter></parameters>"
            + xml.substring(closing));

    Execution current =
        execution("current", project.resolve("orders.hpl").toString(), null, 2_000L);
    Execution previous =
        execution("previous", project.resolve("orders.hpl").toString(), null, 1_000L);
    ExecutionState currentState = state("current", true, false, 3_000L);
    currentState.setStatusDescription("Failed");
    ExecutionState previousState = state("previous", false, false, 1_500L);
    IExecutionInfoLocation location =
        fakeLocation(
            Map.of("current", current, "previous", previous),
            Map.of("current", currentState, "previous", previousState));
    HopExecutionRepository repository =
        new HopExecutionRepository(
            new ProjectFiles(project), null, null, (name, action) -> action.apply(location));
    HopMetadataService metadata =
        new HopMetadataService(new ProjectFiles(project), metadataProvider);
    HopRunConfigurationService runConfigurations =
        new HopRunConfigurationService(metadataProvider, variables);
    HopDiagnosisService service =
        new HopDiagnosisService(
            new ProjectFiles(project),
            repository,
            metadata,
            runConfigurations,
            (channel, includeGeneral, from, to) ->
                Map.of(
                    "count",
                    1,
                    "truncated",
                    false,
                    "events",
                    List.of(
                        Map.of(
                            "timestamp", 2_500L,
                            "level", "ERROR",
                            "message", "password=secret"))));

    Map<String, Object> result = service.diagnose("local", "current", "channel", true, -1, 0, 10);

    assertTrue((Boolean) result.get("evidence_complete"));
    assertTrue(String.valueOf(result.get("facts")).contains("execution.failed"));
    assertTrue(String.valueOf(result.get("possible_causes")).contains("verified=false"));
    assertTrue(String.valueOf(result.get("possible_causes")).contains("logged_error"));
    assertTrue(String.valueOf(result.get("recommendations")).contains("hop_environment_diff"));
    assertTrue(String.valueOf(result.get("evidence")).contains(SensitiveData.REDACTED));
    assertTrue((Boolean) result.get("redaction_applied"));
  }

  private static void saveLocalConfiguration(MemoryMetadataProvider metadataProvider)
      throws Exception {
    PipelineRunConfiguration local = new PipelineRunConfiguration();
    local.setName("local");
    LocalPipelineRunConfiguration engine = new LocalPipelineRunConfiguration();
    engine.setEnginePluginId("Local");
    engine.setEnginePluginName("Local");
    local.setEngineRunConfiguration(engine);
    metadataProvider.getSerializer(PipelineRunConfiguration.class).save(local);
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

  private static ExecutionState state(String id, boolean failed, boolean running, long end) {
    ExecutionState state = new ExecutionState();
    state.setId(id);
    state.setName("orders");
    state.setExecutionType(ExecutionType.Pipeline);
    state.setFailed(failed);
    state.setStatusDescription(running ? "Running" : failed ? "Failed" : "Finished");
    state.setExecutionEndDate(new Date(end));
    return state;
  }

  private static IExecutionInfoLocation fakeLocation(
      Map<String, Execution> executions, Map<String, ExecutionState> states) {
    return (IExecutionInfoLocation)
        Proxy.newProxyInstance(
            IExecutionInfoLocation.class.getClassLoader(),
            new Class<?>[] {IExecutionInfoLocation.class},
            (proxy, method, args) -> {
              String name = method.getName();
              if (name.equals("getExecutionIds")) return List.copyOf(executions.keySet());
              if (name.equals("getExecution")) return executions.get(String.valueOf(args[0]));
              if (name.equals("getExecutionState")) return states.get(String.valueOf(args[0]));
              if (name.equals("findExecutions")) return List.of();
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
