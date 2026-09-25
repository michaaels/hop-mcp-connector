package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HopMcpRuntimeMetricsTest {
  @TempDir Path project;

  @Test
  void runtimeMetricsExposeOnlyBoundedAggregates() throws Exception {
    HopMcpService service =
        new HopMcpService(
            new ProjectFiles(project),
            new Variables(),
            new MemoryMetadataProvider(),
            false,
            false,
            false,
            false,
            null);
    try {
      Map<String, Object> metrics = service.runtimeMetrics();
      assertEquals(Set.of("project_index", "deep_checks", "redaction_applied"), metrics.keySet());
      assertEquals(true, metrics.get("redaction_applied"));
      assertTrue(metrics.get("project_index") instanceof Map<?, ?>);
      assertTrue(metrics.get("deep_checks") instanceof Map<?, ?>);
      String json = JsonUtil.toJson(metrics).toLowerCase(java.util.Locale.ROOT);
      assertFalse(json.contains("password"));
      assertFalse(json.contains("token"));
      assertFalse(json.contains("secret"));
      assertFalse(json.contains("dwh_prod"));
    } finally {
      service.close();
    }
  }
}
