package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.pipeline.PipelineHopMeta;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.apache.hop.pipeline.transforms.dummy.DummyMeta;
import org.apache.hop.workflow.WorkflowHopMeta;
import org.apache.hop.workflow.WorkflowMeta;
import org.apache.hop.workflow.action.ActionMeta;
import org.apache.hop.workflow.actions.dummy.ActionDummy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HopFlowLayoutTest {
  @TempDir Path project;

  @BeforeAll
  static void init() throws Exception {
    HopEnvironment.init();
  }

  private static TransformMeta transform(String name, int x, int y) {
    var transform = new TransformMeta(name, new DummyMeta());
    transform.setLocation(x, y);
    return transform;
  }

  @Test
  void reducesCrossingsAlignsBranchesAndIsIdempotentWithoutRewiring() {
    PipelineMeta meta = new PipelineMeta();
    var a = transform("Reader A", 80, 80);
    var b = transform("Reader B", 80, 220);
    var c = transform("Output C", 440, 220);
    var d = transform("Output D", 440, 80);
    for (var t : List.of(a, b, c, d)) meta.addTransform(t);
    var ac = new PipelineHopMeta(a, c, true);
    var bd = new PipelineHopMeta(b, d, true);
    var disabled = new PipelineHopMeta(c, a, false);
    meta.addPipelineHop(ac);
    meta.addPipelineHop(bd);
    meta.addPipelineHop(disabled);
    Map<String, Object> result = HopFlowLayout.arrange(meta, 80, 80);
    assertEquals(1, ((Map<?, ?>) result.get("before")).get("crossings"));
    assertEquals(0, ((Map<?, ?>) result.get("after")).get("crossings"));
    assertEquals(0, ((Map<?, ?>) result.get("after")).get("component_overlaps"));
    assertEquals(false, result.get("review_required"));
    assertEquals(a.getLocation().y, c.getLocation().y);
    assertEquals(b.getLocation().y, d.getLocation().y);
    assertTrue(c.getLocation().x > a.getLocation().x);
    assertSame(ac, meta.getPipelineHop(0));
    assertSame(bd, meta.getPipelineHop(1));
    assertFalse(disabled.isEnabled());
    assertEquals(0, HopFlowLayout.arrange(meta, 80, 80).get("moved_components"));
  }

  @Test
  void separatesWorkflowFailureFromSuccessAndPreservesConditions() {
    WorkflowMeta meta = new WorkflowMeta();
    var start = new ActionMeta(new ActionDummy());
    start.setName("Start");
    var success = new ActionMeta(new ActionDummy());
    success.setName("Success");
    var failure = new ActionMeta(new ActionDummy());
    failure.setName("Failure");
    for (var action : List.of(start, success, failure)) {
      action.setLocation(50, 50);
      meta.addAction(action);
    }
    var yes = new WorkflowHopMeta(start, success);
    yes.setUnconditional(false);
    yes.setEvaluation(true);
    var no = new WorkflowHopMeta(start, failure);
    no.setUnconditional(false);
    no.setEvaluation(false);
    meta.addWorkflowHop(0, yes);
    meta.addWorkflowHop(1, no);
    HopFlowLayout.arrange(meta, 80, 80);
    assertEquals(start.getLocation().y, success.getLocation().y);
    assertTrue(failure.getLocation().y > success.getLocation().y);
    assertSame(yes, meta.getWorkflowHop(0));
    assertSame(no, meta.getWorkflowHop(1));
    assertTrue(yes.isEvaluation());
    assertFalse(no.isEvaluation());
    assertFalse(no.isUnconditional());
  }

  @Test
  void handlesCyclesMergesDisconnectedAndEmptyGraphsWithinBounds() {
    var nodes = new ArrayList<HopFlowLayout.Node>();
    for (String name : List.of("Start", "Branch", "Join", "Isolated"))
      nodes.add(new HopFlowLayout.Node(name, transform(name, 0, 0), false));
    var edges =
        List.of(
            new HopFlowLayout.Edge(0, 1, false),
            new HopFlowLayout.Edge(1, 2, false),
            new HopFlowLayout.Edge(0, 2, false),
            new HopFlowLayout.Edge(2, 1, false));
    var result =
        assertTimeout(Duration.ofSeconds(2), () -> HopFlowLayout.arrange(nodes, edges, 80, 80));
    assertEquals(0, ((Map<?, ?>) result.get("after")).get("component_overlaps"));
    assertEquals(1, ((Map<?, ?>) result.get("after")).get("backward_hops"));
    assertEquals(true, result.get("review_required"));
    assertEquals(4, edges.size());
    assertEquals(0, HopFlowLayout.arrange(List.of(), List.of(), 80, 80).get("component_count"));
    var original = nodes.getFirst().position().getLocation();
    assertThrows(
        IllegalArgumentException.class, () -> HopFlowLayout.arrange(nodes, edges, 1_000_000, 80));
    assertEquals(original, nodes.getFirst().position().getLocation());
  }

  @Test
  void avoidsStraightBypassThroughAnotherComponentAndRejectsUnusedOptions() {
    var nodes =
        List.of(
            new HopFlowLayout.Node("Input", transform("Input", 0, 0), false),
            new HopFlowLayout.Node("Middle", transform("Middle", 0, 0), false),
            new HopFlowLayout.Node("Output", transform("Output", 0, 0), false));
    var edges =
        List.of(
            new HopFlowLayout.Edge(0, 1, false),
            new HopFlowLayout.Edge(1, 2, false),
            new HopFlowLayout.Edge(0, 2, false));
    var result = HopFlowLayout.arrange(nodes, edges, 80, 80);
    assertEquals(0, ((Map<?, ?>) result.get("after")).get("edges_through_components"));
    assertEquals(0, ((Map<?, ?>) result.get("after")).get("crossings"));
    assertThrows(
        IllegalArgumentException.class,
        () -> HopFlowLayout.validateOptions(Map.of("operation", "auto_layout", "enabled", false)));
  }

  @Test
  void rejectsOversizedLayoutsAndCompletesMaximumSupportedChain() {
    List<HopFlowLayout.Node> nodes = new ArrayList<>();
    List<HopFlowLayout.Edge> edges = new ArrayList<>();
    for (int i = 0; i < HopFlowLayout.MAX_COMPONENTS; i++) {
      nodes.add(new HopFlowLayout.Node("Node " + i, transform("Node " + i, 0, 0), false));
      if (i > 0) edges.add(new HopFlowLayout.Edge(i - 1, i, false));
    }
    assertTimeout(Duration.ofSeconds(5), () -> HopFlowLayout.arrange(nodes, edges, 80, 80));
    nodes.add(new HopFlowLayout.Node("Too many", transform("Too many", 0, 0), false));
    assertThrows(IllegalArgumentException.class, () -> HopFlowLayout.arrange(nodes, edges, 80, 80));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HopFlowLayout.arrange(
                nodes.subList(0, 2),
                java.util.Collections.nCopies(1001, new HopFlowLayout.Edge(0, 1, false)),
                80,
                80));
  }

  @Test
  void nativeMutationPreservesPreviewShaGuardParametersConfigurationAndRollback() throws Exception {
    Variables variables = new Variables();
    var provider = new MemoryMetadataProvider();
    var mutator = new HopDefinitionMutator(new ProjectFiles(project), variables, provider);
    var created =
        mutator.mutate(
            "layout.hpl",
            "pipeline",
            List.of(
                Map.of("operation", "add_parameter", "name", "date", "value", "2026-11-10"),
                Map.of(
                    "operation",
                    "add_component",
                    "plugin_id",
                    "Dummy",
                    "name",
                    "Input",
                    "distribute",
                    false),
                Map.of("operation", "add_component", "plugin_id", "Dummy", "name", "Output"),
                Map.of("operation", "add_hop", "from", "Input", "to", "Output")),
            null,
            true);
    byte[] before = Files.readAllBytes(project.resolve("layout.hpl"));
    var operations = List.<Map<String, Object>>of(Map.of("operation", "auto_layout"));
    var preview = mutator.mutate("layout.hpl", "pipeline", operations, null, false);
    assertEquals(true, preview.get("native_reload_valid"));
    assertArrayEquals(before, Files.readAllBytes(project.resolve("layout.hpl")));
    assertThrows(
        Exception.class,
        () -> mutator.mutate("layout.hpl", "pipeline", operations, "0".repeat(64), true));
    var applied =
        mutator.mutate(
            "layout.hpl", "pipeline", operations, (String) created.get("new_sha256"), true);
    try (var input = Files.newInputStream(project.resolve("layout.hpl"))) {
      var reloaded = new PipelineMeta(input, provider, variables);
      assertEquals("2026-11-10", reloaded.getParameterDefault("date"));
      assertFalse(reloaded.findTransform("Input").isDistributes());
      assertEquals(1, reloaded.nrPipelineHops());
      assertTrue(
          reloaded.findTransform("Output").getLocation().x
              > reloaded.findTransform("Input").getLocation().x);
    }
    mutator.rollback((String) applied.get("transaction_id"), (String) applied.get("new_sha256"));
    assertArrayEquals(before, Files.readAllBytes(project.resolve("layout.hpl")));
  }
}
