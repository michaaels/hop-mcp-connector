package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.apache.hop.core.variables.DescribedVariable;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.pipeline.config.PipelineRunConfiguration;
import org.apache.hop.pipeline.engines.local.LocalPipelineRunConfiguration;
import org.junit.jupiter.api.Test;

class HopRunConfigurationServiceTest {
  @Test
  void redactsMixedWindowsAndUnixAliasChainsAndRetainsUnresolvedWindowsReferences()
      throws Exception {
    MemoryMetadataProvider provider = new MemoryMetadataProvider();
    PipelineRunConfiguration configuration = new PipelineRunConfiguration();
    configuration.setName("windows-aliases");
    configuration
        .getConfigurationVariables()
        .addAll(
            List.of(
                new DescribedVariable("FIRST_ALIAS", "%%API_TOKEN%%", ""),
                new DescribedVariable("SECOND_ALIAS", "${FIRST_ALIAS}", ""),
                new DescribedVariable("THIRD_ALIAS", "%%SECOND_ALIAS%%", ""),
                new DescribedVariable("PUBLIC_PATH", "%%PROJECT_HOME%%/data", ""),
                new DescribedVariable("UNRESOLVED", "%%NOT_CONFIGURED%%", "")));
    provider.getSerializer(PipelineRunConfiguration.class).save(configuration);
    Variables variables = new Variables();
    variables.setVariable("API_TOKEN", "windows-fixture-credential");
    variables.setVariable("PROJECT_HOME", "C:/project");
    var result =
        new HopRunConfigurationService(provider, variables).resolve("pipeline", "windows-aliases");
    List<?> rows = (List<?>) result.get("variables");
    for (int index = 0; index < 3; index++) {
      assertEquals(SensitiveData.REDACTED, ((Map<?, ?>) rows.get(index)).get("value"));
      assertEquals(true, ((Map<?, ?>) rows.get(index)).get("redacted"));
    }
    assertEquals("C:/project/data", ((Map<?, ?>) rows.get(3)).get("value"));
    assertEquals(false, ((Map<?, ?>) rows.get(4)).get("resolved"));
    assertTrue(((List<?>) result.get("unresolved_references")).contains("NOT_CONFIGURED"));
    assertTrue(!JsonUtil.toJson(result).contains("windows-fixture-credential"));
  }

  @Test
  void clearsAliasSensitivityWhenAConfiguredNameIsReplacedWithAPublicValue() throws Exception {
    MemoryMetadataProvider provider = new MemoryMetadataProvider();
    PipelineRunConfiguration configuration = new PipelineRunConfiguration();
    configuration.setName("rebound-alias");
    configuration
        .getConfigurationVariables()
        .addAll(
            List.of(
                new DescribedVariable("PUBLIC_ALIAS", "${API_TOKEN}", ""),
                new DescribedVariable("PUBLIC_ALIAS", "/public", ""),
                new DescribedVariable("FOLLOWING_ALIAS", "${PUBLIC_ALIAS}", "")));
    provider.getSerializer(PipelineRunConfiguration.class).save(configuration);
    Variables variables = new Variables();
    variables.setVariable("API_TOKEN", "rebound-fixture-credential");
    var result =
        new HopRunConfigurationService(provider, variables).resolve("pipeline", "rebound-alias");
    List<?> rows = (List<?>) result.get("variables");
    assertEquals(SensitiveData.REDACTED, ((Map<?, ?>) rows.get(0)).get("value"));
    assertEquals("/public", ((Map<?, ?>) rows.get(2)).get("value"));
    assertEquals(false, ((Map<?, ?>) rows.get(2)).get("redacted"));
    assertTrue(!JsonUtil.toJson(result).contains("rebound-fixture-credential"));
  }

  @Test
  void withholdsOpaqueResolverAndHexExpressionsWithoutCallingExternalResolvers() throws Exception {
    MemoryMetadataProvider provider = new MemoryMetadataProvider();
    PipelineRunConfiguration configuration = new PipelineRunConfiguration();
    configuration.setName("opaque");
    // An incomplete resolver token remains literal in Hop and never opens a backend.
    configuration
        .getConfigurationVariables()
        .addAll(
            List.of(
                new DescribedVariable("OPAQUE", "#{not-closed", ""),
                new DescribedVariable("HEX", "$[61]", "")));
    provider.getSerializer(PipelineRunConfiguration.class).save(configuration);
    var result =
        new HopRunConfigurationService(provider, new Variables()).resolve("pipeline", "opaque");
    for (Object item : (List<?>) result.get("variables")) {
      assertEquals(SensitiveData.REDACTED, ((Map<?, ?>) item).get("value"));
      assertEquals(true, ((Map<?, ?>) item).get("redacted"));
    }
  }

  @Test
  void withholdsSecretReferencesTooLongForTheDependencyParser() throws Exception {
    MemoryMetadataProvider provider = new MemoryMetadataProvider();
    PipelineRunConfiguration configuration = new PipelineRunConfiguration();
    configuration.setName("long-reference");
    String secretName = "API_TOKEN_" + "X".repeat(256);
    configuration
        .getConfigurationVariables()
        .add(new DescribedVariable("PUBLIC_VALUE", "${" + secretName + "}", "Long reference"));
    provider.getSerializer(PipelineRunConfiguration.class).save(configuration);
    Variables variables = new Variables();
    variables.setVariable(secretName, "long-reference-fixture-credential");
    Map<String, Object> result =
        new HopRunConfigurationService(provider, variables).resolve("pipeline", "long-reference");
    Map<?, ?> row = (Map<?, ?>) ((List<?>) result.get("variables")).get(0);
    assertEquals(SensitiveData.REDACTED, row.get("value"));
    assertTrue(!JsonUtil.toJson(result).contains("long-reference-fixture-credential"));
  }

  @Test
  void followsOnlyReferencedInheritedAndParameterAliases() throws Exception {
    MemoryMetadataProvider provider = new MemoryMetadataProvider();
    PipelineRunConfiguration configuration = new PipelineRunConfiguration();
    configuration.setName("inherited");
    configuration
        .getConfigurationVariables()
        .add(new DescribedVariable("PUBLIC_VALUE", "${BINDING}", "Public alias"));
    provider.getSerializer(PipelineRunConfiguration.class).save(configuration);
    Variables variables = new Variables();
    variables.setVariable("VISIBLE_ALIAS", "${API_TOKEN}");
    variables.setVariable("API_TOKEN", "inherited-fixture-credential");
    Map<String, Object> result =
        new HopRunConfigurationService(provider, variables)
            .resolveConfiguration(
                "pipeline.hpl", "inherited", Map.of("BINDING", "${VISIBLE_ALIAS}"));
    Map<?, ?> row = (Map<?, ?>) ((List<?>) result.get("variables")).get(0);
    assertEquals(SensitiveData.REDACTED, row.get("value"));
    assertEquals(true, row.get("redacted"));
    assertTrue(!JsonUtil.toJson(result).contains("inherited-fixture-credential"));
  }

  @Test
  void withholdsValuesWhenDependencyInspectionExceedsItsBound() throws Exception {
    MemoryMetadataProvider provider = new MemoryMetadataProvider();
    PipelineRunConfiguration configuration = new PipelineRunConfiguration();
    configuration.setName("bounded");
    Variables variables = new Variables();
    StringBuilder references = new StringBuilder();
    for (int index = 0; index <= HopRunConfigurationService.MAX_UNRESOLVED_REFERENCES; index++) {
      variables.setVariable("V" + index, "safe");
      references.append("${V").append(index).append('}');
    }
    configuration
        .getConfigurationVariables()
        .add(
            new DescribedVariable(
                "PUBLIC_VALUE", references.toString(), "Bounded dependency graph"));
    provider.getSerializer(PipelineRunConfiguration.class).save(configuration);
    Map<String, Object> result =
        new HopRunConfigurationService(provider, variables).resolve("pipeline", "bounded");
    Map<?, ?> row = (Map<?, ?>) ((List<?>) result.get("variables")).get(0);
    assertEquals(SensitiveData.REDACTED, row.get("value"));
    assertEquals(true, row.get("redacted"));
  }

  @Test
  void redactsSecretsPropagatedThroughConfiguredAliases() throws Exception {
    MemoryMetadataProvider provider = new MemoryMetadataProvider();
    PipelineRunConfiguration configuration = new PipelineRunConfiguration();
    configuration.setName("aliases");
    configuration
        .getConfigurationVariables()
        .addAll(
            List.of(
                new DescribedVariable("FIRST_ALIAS", "${DB_PASSWORD}", "First alias"),
                new DescribedVariable("SECOND_ALIAS", "${FIRST_ALIAS}", "Second alias"),
                new DescribedVariable("PUBLIC_PATH", "${PROJECT_HOME}/data", "Public path")));
    provider.getSerializer(PipelineRunConfiguration.class).save(configuration);
    Variables variables = new Variables();
    variables.setVariable("DB_PASSWORD", "opaque-fixture-credential");
    variables.setVariable("PROJECT_HOME", "C:/project");
    variables.setVariable("UNREFERENCED_ENVIRONMENT_VALUE", "environment-fixture-private");

    Map<String, Object> result =
        new HopRunConfigurationService(provider, variables).resolve("pipeline", "aliases");
    List<?> rows = (List<?>) result.get("variables");
    for (int index = 0; index < 2; index++) {
      assertEquals(SensitiveData.REDACTED, ((Map<?, ?>) rows.get(index)).get("value"));
      assertEquals(true, ((Map<?, ?>) rows.get(index)).get("redacted"));
    }
    assertEquals("C:/project/data", ((Map<?, ?>) rows.get(2)).get("value"));
    assertTrue(!JsonUtil.toJson(result).contains("opaque-fixture-credential"));
    assertTrue(!JsonUtil.toJson(result).contains("environment-fixture-private"));
    assertTrue(!JsonUtil.toJson(result).contains("UNREFERENCED_ENVIRONMENT_VALUE"));
  }

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

    HopRunConfigurationService service = new HopRunConfigurationService(provider, new Variables());
    Map<String, Object> result =
        service.resolveConfiguration(
            "pipelines/orders.hpl", "local", Map.of("PARAM_DIR", "C:/data"));

    assertEquals("pipelines/orders.hpl", result.get("path"));
    assertEquals("local", result.get("run_configuration"));
    assertEquals(
        "C:/data/out", ((Map<?, ?>) ((List<?>) result.get("variables")).get(0)).get("value"));
    assertEquals(
        "Local",
        ((Map<?, ?>) result.get("effective_configuration")).get("engine")
                instanceof Map<?, ?> engineMap
            ? engineMap.get("plugin_id")
            : "");
  }
}
