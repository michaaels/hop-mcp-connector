package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.apache.hop.core.variables.DescribedVariable;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.pipeline.config.PipelineRunConfiguration;
import org.apache.hop.pipeline.engines.local.LocalPipelineRunConfiguration;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.junit.jupiter.api.Test;

class HopRunConfigurationServiceTest {
  @Test
  void resolvesNativePipelineVariablesAndRedactsSecrets() throws Exception {
    MemoryMetadataProvider provider = new MemoryMetadataProvider();
    PipelineRunConfiguration configuration = new PipelineRunConfiguration();
    configuration.setName("prod");
    configuration.setDescription("Production pipeline");
    configuration
        .getConfigurationVariables()
        .addAll(
            List.of(
                new DescribedVariable("OUTPUT_DIR", "${PROJECT_HOME}/data", "Output path"),
                new DescribedVariable("DB_PASSWORD", "super-secret", "Database password"),
                new DescribedVariable("MISSING", "${NOT_SET}/value", "Unresolved value")));
    LocalPipelineRunConfiguration engine = new LocalPipelineRunConfiguration();
    engine.setEnginePluginId("Local");
    engine.setEnginePluginName("Local");
    configuration.setEngineRunConfiguration(engine);
    provider.getSerializer(PipelineRunConfiguration.class).save(configuration);

    Variables variables = new Variables();
    variables.setVariable("PROJECT_HOME", "C:/project");
    HopRunConfigurationService service = new HopRunConfigurationService(provider, variables);

    Map<String, Object> result = service.resolve("pipeline", "prod");
    List<?> resolved = (List<?>) result.get("variables");

    assertEquals("Local", ((Map<?, ?>) result.get("engine")).get("plugin_id"));
    assertEquals("C:/project/data", ((Map<?, ?>) resolved.get(0)).get("value"));
    assertEquals(SensitiveData.REDACTED, ((Map<?, ?>) resolved.get(1)).get("value"));
    assertTrue(((List<?>) result.get("unresolved_references")).contains("NOT_SET"));
    assertTrue(!JsonUtil.toJson(result).contains("super-secret"));
  }

  @Test
  void resolvesDefinitionWithParametersAndReturnsEffectiveConfiguration() throws Exception {
    MemoryMetadataProvider provider = new MemoryMetadataProvider();
    PipelineRunConfiguration configuration = new PipelineRunConfiguration();
    configuration.setName("local");
    configuration
        .getConfigurationVariables()
        .add(new DescribedVariable("OUTPUT_DIR", "${PARAM_DIR}/out", "Output directory"));
    LocalPipelineRunConfiguration engine = new LocalPipelineRunConfiguration();
    engine.setEnginePluginId("Local");
    engine.setEnginePluginName("Local");
    configuration.setEngineRunConfiguration(engine);
    provider.getSerializer(PipelineRunConfiguration.class).save(configuration);

    HopRunConfigurationService service =
        new HopRunConfigurationService(provider, new Variables());
    Map<String, Object> result =
        service.resolveConfiguration("pipelines/orders.hpl", "local", Map.of("PARAM_DIR", "C:/data"));

    assertEquals("pipelines/orders.hpl", result.get("path"));
    assertEquals("local", result.get("run_configuration"));
    assertEquals("C:/data/out", ((Map<?, ?>) ((List<?>) result.get("variables")).get(0)).get("value"));
    assertEquals("Local", ((Map<?, ?>) result.get("effective_configuration")).get("engine") instanceof Map<?, ?> engineMap
        ? engineMap.get("plugin_id")
        : "");
  }
}
