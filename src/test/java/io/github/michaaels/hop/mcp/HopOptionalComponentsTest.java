package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.row.RowMeta;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.ITransformMeta;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Real plugin jars are test-only dependencies of the Hop 2.20 compatibility profile. */
@EnabledIfSystemProperty(named = "hop.compatibility.components", matches = "true")
class HopOptionalComponentsTest {
  @TempDir Path project;

  @BeforeAll
  static void initializePlugins() throws Exception {
    HopEnvironment.init();
  }

  @Test
  void createsAndReloadsWatchFilesWithFiniteRuntimeAndStableIdentity() throws Exception {
    Map<String, Object> properties =
        Map.of(
            "directory",
            project.toRealPath().toString(),
            "watchId",
            "mcp-fixture",
            "maximumRunTime",
            "1",
            "maximumRunTimeUnit",
            "MINUTES");
    ITransformMeta component = createAndReload("watch.hpl", "WatchFiles", properties, Map.of());
    String xml = component.getXml();
    assertTrue(xml.contains("mcp-fixture"), xml);
    assertTrue(xml.contains("<maximumRunTime>1</maximumRunTime>"), xml);
    assertTrue(xml.contains("<maximumRunTimeUnit>MINUTES</maximumRunTimeUnit>"), xml);
    Map<String, Object> schema = authoring().schema("pipeline", "WatchFiles");
    assertEquals(true, schema.get("native_injection_supported"));
    assertTrue(schema.toString().contains("directory"));
    assertEquals(
        List.of("Local"),
        List.of(
            component
                .getClass()
                .getAnnotation(org.apache.hop.core.annotations.Transform.class)
                .supportedEngines()));
  }

  @Test
  void createsAndReloadsOpenFormulaAndResolvesItsOutputFields() throws Exception {
    ITransformMeta component =
        createAndReload(
            "formula.hpl",
            "OpenFormula",
            Map.of(),
            Map.of(
                "FORMULAS",
                List.of(
                    Map.of(
                        "field_name",
                        "calculated",
                        "formula",
                        "1+2",
                        "value_type",
                        "Number",
                        "value_length",
                        -1,
                        "value_precision",
                        -1))));
    RowMeta fields = new RowMeta();
    component.getFields(
        fields, "Fixture", null, null, new Variables(), new MemoryMetadataProvider());
    assertNotNull(fields.searchValueMeta("calculated"));
    assertEquals("Number", fields.searchValueMeta("calculated").getTypeDesc());
    assertEquals("OpenFormulaMeta", component.getClass().getSimpleName());
    assertFalse(component.getXml().contains("<type>Formula</type>"));
  }

  @Test
  void preservesPipelineExecutorRetrySettingsAcrossSemanticEdits() throws Exception {
    var transform =
        authoring().createTransform("PipelineExecutor", "Fixture", Map.of(), Map.of(), 0, 0);
    ITransformMeta component = transform.getTransform();
    // Hop's legacy injection schema does not expose these new metadata fields. Seed them through
    // the real 2.20 setters, then verify that an ordinary MCP edit preserves their native XML.
    component.getClass().getMethod("setRetryAttempts", String.class).invoke(component, "3");
    component.getClass().getMethod("setRetryDelay", String.class).invoke(component, "50");
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("Retry fixture");
    pipeline.addTransform(transform);
    Files.writeString(project.resolve("executor.hpl"), pipeline.getXml(new Variables()));
    String xml = component.getXml();
    assertTrue(xml.contains("<retry_attempts>3</retry_attempts>"), xml);
    assertTrue(xml.contains("<retry_delay>50</retry_delay>"), xml);

    HopDefinitionMutator mutator = mutator();
    String expected = ProjectFiles.sha256(Files.readAllBytes(project.resolve("executor.hpl")));
    mutator.mutate(
        "executor.hpl",
        "pipeline",
        List.of(
            Map.of("operation", "rename_component", "component", "Fixture", "new_name", "Renamed")),
        expected,
        true);
    PipelineMeta reloaded =
        new PipelineMeta(
            project.resolve("executor.hpl").toString(),
            new MemoryMetadataProvider(),
            new Variables());
    String renamedXml = reloaded.findTransform("Renamed").getTransform().getXml();
    assertTrue(renamedXml.contains("<retry_attempts>3</retry_attempts>"), renamedXml);
    assertTrue(renamedXml.contains("<retry_delay>50</retry_delay>"), renamedXml);
  }

  private ITransformMeta createAndReload(
      String path, String plugin, Map<String, Object> properties, Map<String, Object> groups)
      throws Exception {
    mutator()
        .mutate(
            path,
            "pipeline",
            List.of(
                Map.of(
                    "operation",
                    "add_component",
                    "plugin_id",
                    plugin,
                    "name",
                    "Fixture",
                    "properties",
                    properties,
                    "property_groups",
                    groups)),
            null,
            true);
    assertTrue(Files.isRegularFile(project.resolve(path)));
    PipelineMeta pipeline =
        new PipelineMeta(
            project.resolve(path).toString(), new MemoryMetadataProvider(), new Variables());
    return pipeline.findTransform("Fixture").getTransform();
  }

  private HopDefinitionMutator mutator() throws Exception {
    return new HopDefinitionMutator(
        new ProjectFiles(project.toRealPath()), new Variables(), new MemoryMetadataProvider());
  }

  private HopComponentAuthoring authoring() {
    return new HopComponentAuthoring(PluginRegistry.getInstance(), new MemoryMetadataProvider());
  }
}
