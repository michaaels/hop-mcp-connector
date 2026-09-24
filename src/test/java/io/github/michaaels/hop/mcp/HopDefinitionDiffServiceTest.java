package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
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

class HopDefinitionDiffServiceTest {
  @TempDir Path project;

  @BeforeAll
  static void initializeHopPlugins() throws Exception {
    HopEnvironment.init();
  }

  @Test
  void comparesNativePipelineComponentsHopsPropertiesAndReferences() throws Exception {
    Variables variables = new Variables();
    MemoryMetadataProvider metadataProvider = new MemoryMetadataProvider();
    PipelineMeta before = pipeline("Compared", "uses OLD_REF.hpl", "Input");
    PipelineMeta after = pipeline("Compared", "uses NEW_REF.hwf", "Input");
    after.addTransform(new TransformMeta("Dummy", "Output", new DummyMeta()));
    after.addPipelineHop(
        after.nrPipelineHops(),
        new PipelineHopMeta(after.findTransform("Input"), after.findTransform("Output"), true));
    Files.writeString(
        project.resolve("before.hpl"),
        withParameter(withReference(before.getXml(variables), "OLD_REF.hpl"), "old"));
    Files.writeString(
        project.resolve("after.hpl"),
        withParameter(withReference(after.getXml(variables), "NEW_REF.hwf"), "new"));

    Map<String, Object> result =
        new HopDefinitionDiffService(new ProjectFiles(project), variables, metadataProvider)
            .compare("before.hpl", "after.hpl");

    assertEquals("pipeline", result.get("kind"));
    assertFalse((Boolean) result.get("identical"));
    assertEquals(List.of("Output"), result.get("components_added"));
    assertTrue(String.valueOf(result.get("components_changed")).contains("description"));
    assertTrue(String.valueOf(result.get("hops_added")).contains("Input"));
    assertTrue(String.valueOf(result.get("parameters_changed")).contains("PARAM"));
    assertEquals(List.of("NEW_REF.hwf"), result.get("metadata_references_added"));
    assertEquals(List.of("OLD_REF.hpl"), result.get("metadata_references_removed"));
    assertEquals(true, result.get("redaction_applied"));
    assertEquals(true, result.get("comparison_complete"));
  }

  @Test
  void acceptsOnlyMatchingProjectRelativeDefinitions() throws Exception {
    Variables variables = new Variables();
    MemoryMetadataProvider metadataProvider = new MemoryMetadataProvider();
    Files.writeString(project.resolve("one.hpl"), pipeline("One", "", "Input").getXml(variables));
    Files.writeString(project.resolve("two.hwf"), "<workflow></workflow>");
    HopDefinitionDiffService service =
        new HopDefinitionDiffService(new ProjectFiles(project), variables, metadataProvider);

    assertThrows(IllegalArgumentException.class, () -> service.compare("one.hpl", "two.hwf"));
    assertThrows(Exception.class, () -> service.compare("../outside.hpl", "one.hpl"));
  }

  @Test
  void comparesNativeWorkflowActionsAndHops() throws Exception {
    Variables variables = new Variables();
    MemoryMetadataProvider metadataProvider = new MemoryMetadataProvider();
    WorkflowMeta before = workflow("Before");
    WorkflowMeta after = workflow("After");
    ActionDummy finish = new ActionDummy();
    finish.setName("Finish");
    after.addAction(new ActionMeta(finish));
    after.addWorkflowHop(
        after.nrWorkflowHops(),
        new WorkflowHopMeta(after.findAction("Start"), after.findAction("Finish")));
    Files.writeString(project.resolve("before.hwf"), before.getXml(variables));
    Files.writeString(project.resolve("after.hwf"), after.getXml(variables));

    Map<String, Object> result =
        new HopDefinitionDiffService(new ProjectFiles(project), variables, metadataProvider)
            .compare("before.hwf", "after.hwf");

    assertEquals("workflow", result.get("kind"));
    assertTrue(String.valueOf(result.get("components_added")).contains("Finish"));
    assertTrue(String.valueOf(result.get("hops_added")).contains("Start"));
  }

  private static PipelineMeta pipeline(String name, String description, String inputName) {
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setNameSynchronizedWithFilename(false);
    pipeline.setName(name);
    pipeline.setDescription(description);
    TransformMeta input = new TransformMeta("Dummy", inputName, new DummyMeta());
    input.setDescription(description);
    pipeline.addTransform(input);
    return pipeline;
  }

  private static WorkflowMeta workflow(String name) {
    WorkflowMeta workflow = new WorkflowMeta();
    workflow.setNameSynchronizedWithFilename(false);
    workflow.setName(name);
    ActionDummy start = new ActionDummy();
    start.setName("Start");
    workflow.addAction(new ActionMeta(start));
    return workflow;
  }

  private static String withReference(String xml, String reference) {
    int closing = xml.lastIndexOf("</");
    if (closing < 0) throw new IllegalArgumentException("Serialized pipeline has no closing tag");
    return xml.substring(0, closing)
        + "<note>uses "
        + reference
        + "</note>"
        + xml.substring(closing);
  }

  private static String withParameter(String xml, String defaultValue) {
    int closing = xml.lastIndexOf("</");
    return xml.substring(0, closing)
        + "<parameters><parameter><name>PARAM</name><default_value>"
        + defaultValue
        + "</default_value><description>test parameter</description></parameter></parameters>"
        + xml.substring(closing);
  }
}
