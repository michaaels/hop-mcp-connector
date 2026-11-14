package io.github.michaaels.hop.mcp;

import static io.github.michaaels.hop.mcp.HopGuidedPresentation.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.apache.hop.pipeline.*;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.apache.hop.pipeline.transforms.dummy.DummyMeta;
import org.apache.hop.workflow.*;
import org.apache.hop.workflow.action.ActionMeta;
import org.apache.hop.workflow.actions.dummy.ActionDummy;
import org.junit.jupiter.api.Test;

class HopNativeEditorTest {
  @Test
  void pipelineFramesUseConfiguredNativeTransformsAndNativeHopMutationMethods() {
    var meta = new PipelineMeta();
    var a = new TransformMeta("Dummy", "A", new DummyMeta());
    var b = new TransformMeta("Dummy", "B", new DummyMeta());
    a.setDescription("Native configuration remains intact");
    var cache = Map.of("A", a, "B", b);
    var nodes = List.of(new Node("A", "Dummy", 120, 80), new Node("B", "Dummy", 300, 80));
    HopNativeEditor.populate(meta, nodes.subList(0, 1), List.of(), "A", cache);
    assertSame(a, meta.findTransform("A"));
    assertNull(meta.findTransform("B"));
    HopNativeEditor.populate(meta, nodes, List.of(new Edge("A", "B", false, -1)), "B", cache);
    assertEquals(2, meta.getTransforms().size());
    assertEquals("Native configuration remains intact", a.getDescription());
    assertTrue(b.isSelected());
    assertEquals(120, a.getLocation().x);
    assertTrue(meta.getPipelineHops().getFirst().isErrorHop());
    assertFalse(meta.getPipelineHops().getFirst().isEnabled());
    HopNativeEditor.populate(meta, List.of(), List.of(), "", cache);
    assertTrue(meta.getPipelineHops().isEmpty());
    assertFalse(meta.hasChanged());
  }

  @Test
  void workflowFramesKeepActualActionsAndConditionalBranches() {
    var meta = new WorkflowMeta();
    var a = new ActionMeta(new ActionDummy());
    a.setName("A");
    var b = new ActionMeta(new ActionDummy());
    b.setName("B");
    b.setDescription("Configured action");
    var cache = Map.of("A", a, "B", b);
    var nodes = List.of(new Node("A", "DUMMY", 80, 80), new Node("B", "DUMMY", 280, 80));
    HopNativeEditor.populate(meta, nodes, List.of(new Edge("A", "B", true, -1)), "A", cache);
    assertSame(b, meta.findAction("B"));
    assertEquals("Configured action", b.getDescription());
    var hop = meta.getWorkflowHops().getFirst();
    assertFalse(hop.isUnconditional());
    assertFalse(hop.isEvaluation());
    assertTrue(hop.isEnabled());
    HopNativeEditor.populate(meta, nodes.subList(1, 2), List.of(), "B", cache);
    assertEquals(1, meta.getActions().size());
    assertNull(meta.findAction("A"));
    assertTrue(meta.getWorkflowHops().isEmpty());
    assertFalse(meta.hasChanged());
  }
}
