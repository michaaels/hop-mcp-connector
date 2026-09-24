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
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.config.PipelineRunConfiguration;
import org.apache.hop.pipeline.engines.local.LocalPipelineRunConfiguration;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.apache.hop.pipeline.transforms.dummy.DummyMeta;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HopEnvironmentDiffServiceTest {
  @TempDir Path project;

  @BeforeAll
  static void initializeHopPlugins() throws Exception {
    HopEnvironment.init();
  }

  @Test
  void comparesNonSensitiveEnvironmentSectionsAndReportsUnresolvedVariables() throws Exception {
    Variables variables = new Variables();
    MemoryMetadataProvider metadataProvider = new MemoryMetadataProvider();
    saveLocalConfiguration(metadataProvider);
    PipelineMeta pipeline = pipeline("Environment");
    Files.writeString(
        project.resolve("dev.hpl"),
        withEnvironment(
            pipeline.getXml(variables), "SALES_DEV", "thread-dev", "secret-dev", "4"));
    Files.writeString(
        project.resolve("prod.hpl"),
        withEnvironment(
            pipeline.getXml(variables), "SALES", "thread-prod", null, "8"));

    Map<String, Object> result =
        new HopEnvironmentDiffService(
                new ProjectFiles(project),
                variables,
                new HopRunConfigurationService(metadataProvider, variables))
            .compare("dev.hpl", "prod.hpl", "local", "local", Map.of(), Map.of());

    assertEquals("pipeline", result.get("kind"));
    assertFalse((Boolean) result.get("identical"));
    assertTrue(String.valueOf(result.get("variables_changed")).contains("SALES_DEV"));
    assertTrue(String.valueOf(result.get("metadata_references_changed")).contains("DEV_DB"));
    assertTrue(String.valueOf(result.get("parameter_defaults_changed")).contains("THREADS"));
    assertTrue(String.valueOf(result.get("unresolved_variables")).contains("MISSING"), result.toString());
    assertTrue(String.valueOf(result.get("variables_changed")).contains(SensitiveData.REDACTED));
    assertTrue((Boolean) result.get("redaction_applied"));
    assertTrue((Boolean) result.get("comparison_complete"));
}

  @Test
  void requiresMatchingProjectRelativeDefinitions() throws Exception {
    Variables variables = new Variables();
    MemoryMetadataProvider metadataProvider = new MemoryMetadataProvider();
    saveLocalConfiguration(metadataProvider);
    Files.writeString(project.resolve("one.hpl"), pipeline("One").getXml(variables));
    Files.writeString(project.resolve("two.hwf"), "<workflow></workflow>");
    HopEnvironmentDiffService service =
        new HopEnvironmentDiffService(
            new ProjectFiles(project),
            variables,
            new HopRunConfigurationService(metadataProvider, variables));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.compare("one.hpl", "two.hwf", "local", "local", Map.of(), Map.of()));
    assertThrows(
        Exception.class,
        () -> service.compare("../outside.hpl", "one.hpl", "local", "local", Map.of(), Map.of()));
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

  private static PipelineMeta pipeline(String name) {
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setNameSynchronizedWithFilename(false);
    pipeline.setName(name);
    pipeline.addTransform(new TransformMeta("Dummy", "Input", new DummyMeta()));
    return pipeline;
  }

  private static String withEnvironment(
      String xml, String schema, String thread, String token, String threads) {
    int closing = xml.lastIndexOf("</");
    return xml.substring(0, closing)
        + "<variables><variable><name>DB_SCHEMA</name><value>"
        + schema
        + "</value></variable><variable><name>THREAD_VAR</name><value>"
        + thread
        + "</value></variable>"
        + (token == null
            ? ""
            : "<variable><name>API_TOKEN</name><value>" + token + "</value></variable>")
        + "</variables>"
        + "<environment><connection>"
        + (schema.equals("SALES_DEV") ? "DEV_DB" : "PROD_DB")
        + "</connection><metadata>"
        + schema
        + "</metadata><note>${MISSING}</note></environment>"
        + "<parameters><parameter><name>THREADS</name><default_value>"
        + threads
        + "</default_value></parameter></parameters>"
        + xml.substring(closing);
  }
}
