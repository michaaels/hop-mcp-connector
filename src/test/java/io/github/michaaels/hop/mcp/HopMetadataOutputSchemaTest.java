package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.JacksonJsonSchemaValidatorSupplier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.hop.core.variables.DescribedVariable;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.pipeline.config.PipelineRunConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HopMetadataOutputSchemaTest {
  @TempDir Path project;
  private final JsonSchemaValidator validator = new JacksonJsonSchemaValidatorSupplier().get();
  private final Map<String, Object> schema = HopMcpServer.outputSchemaFor("hop_metadata_get");

  @Test
  void projectsNativeVariableRowsWithoutExposingNamedSecrets() throws Exception {
    MemoryMetadataProvider provider = new MemoryMetadataProvider();
    PipelineRunConfiguration configuration = new PipelineRunConfiguration();
    configuration.setName("fixture");
    configuration
        .getConfigurationVariables()
        .addAll(
            List.of(
                new DescribedVariable(
                    "DB_PASSWORD", "metadata-fixture-credential", "Database password"),
                new DescribedVariable("PUBLIC_PATH", "/data", "Public directory")));
    provider.getSerializer(PipelineRunConfiguration.class).save(configuration);
    Map<String, Object> result =
        new HopMetadataService(new ProjectFiles(project.toRealPath()), provider)
            .get("PipelineRunConfiguration", "fixture");
    assertFalse(JsonUtil.toJson(result).contains("metadata-fixture-credential"));
    var validation = validator.validate(schema, result);
    assertTrue(validation.valid(), validation.errorMessage());
    assertTrue(JsonUtil.toJson(result).contains(SensitiveData.REDACTED));
    assertTrue(JsonUtil.toJson(result).contains("/data"));
  }

  @Test
  void advertisesACompactSchemaThatRetainsTypedErrorResults() {
    assertTrue(
        JsonUtil.toJson(schema).getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            < 32 * 1024);
    var error =
        validator.validate(
            schema,
            Map.of(
                "code",
                "METADATA_NOT_FOUND",
                "category",
                "NOT_FOUND",
                "message",
                "Metadata was not found.",
                "retryable",
                false));
    assertTrue(error.valid(), error.errorMessage());
  }

  @Test
  void acceptsNativeVariableRowsAndBoundedNestedMetadata() {
    assertValid(
        Map.of(
            "configurationVariables",
            List.of(
                Map.of(
                    "name",
                    "PUBLIC_ALIAS",
                    "value",
                    "${API_TOKEN}",
                    "description",
                    "Configured alias"))));
    Object nested = "leaf";
    for (int depth = 0; depth < HopMetadataService.MAX_METADATA_DEPTH; depth++) {
      nested = Map.of("child", nested);
    }
    assertValid(Map.of("nested", nested));
    assertFalse(validate(Map.of("nested", Map.of("child", nested))).valid());
  }

  @Test
  void retainsCollectionAndNestedObjectBounds() {
    List<Object> values = new ArrayList<>();
    for (int index = 0; index < HopMetadataService.MAX_METADATA_COLLECTION_ITEMS; index++)
      values.add("item");
    values.add("<truncated>");
    assertValid(Map.of("values", values));
    values.add("overflow");
    assertFalse(validate(Map.of("values", values)).valid());

    Map<String, Object> nested = new LinkedHashMap<>();
    for (int index = 0; index < HopMetadataService.MAX_METADATA_FIELDS; index++)
      nested.put("field" + index, "value");
    assertValid(Map.of("nested", nested));
    nested.put("overflow", "value");
    assertFalse(validate(Map.of("nested", nested)).valid());
  }

  @Test
  void accountsForTheThreeRootIdentityFieldsAlongsideNativeProperties() {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("name", "fixture");
    metadata.put("virtual_path", "");
    metadata.put("metadata_provider", "memory");
    for (int index = 0; index < HopMetadataService.MAX_METADATA_FIELDS; index++)
      metadata.put("field" + index, "value");
    assertValid(metadata);
    metadata.put("overflow", "value");
    assertFalse(validate(metadata).valid());
  }

  private void assertValid(Map<String, Object> metadata) {
    var result = validate(metadata);
    assertTrue(result.valid(), result.errorMessage());
  }

  private JsonSchemaValidator.ValidationResponse validate(Map<String, Object> metadata) {
    return validator.validate(
        schema,
        Map.of(
            "type",
            "pipeline-run-configuration",
            "name",
            "fixture",
            "metadata",
            metadata,
            "redaction_applied",
            true));
  }
}
