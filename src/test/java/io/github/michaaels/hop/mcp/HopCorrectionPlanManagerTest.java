package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HopCorrectionPlanManagerTest {
  @TempDir Path project;

  @BeforeAll
  static void initializeHopPlugins() throws Exception {
    HopEnvironment.init();
  }

  @Test
  void preparesAndAppliesSingleUsePlanThroughNativeMutation() throws Exception {
    HopCorrectionPlanManager manager = manager();
    List<Map<String, Object>> operations = workflowOperations();

    Map<String, Object> prepared = manager.prepare("planned.hwf", "workflow", operations);

    assertEquals("prepared", prepared.get("state"));
    assertEquals(false, prepared.get("target_exists"));
    assertEquals(false, prepared.get("auto_apply"));
    assertEquals(3, prepared.get("operation_count"));
    assertFalse(Files.exists(project.resolve("planned.hwf")));
    assertEquals(64, String.valueOf(prepared.get("plan_sha256")).length());

    Map<String, Object> applied =
        manager.apply(
            String.valueOf(prepared.get("plan_id")), String.valueOf(prepared.get("plan_sha256")));

    assertEquals("applied", applied.get("state"));
    assertTrue(Files.exists(project.resolve("planned.hwf")));
    assertEquals(true, ((Map<?, ?>) applied.get("mutation")).get("applied"));
    Map<String, Object> status = manager.status(String.valueOf(prepared.get("plan_id")));
    assertEquals(2, ((List<?>) status.get("audit")).size());
    assertThrows(
        IllegalStateException.class,
        () ->
            manager.apply(
                String.valueOf(prepared.get("plan_id")),
                String.valueOf(prepared.get("plan_sha256"))));
  }

  @Test
  void rejectsWrongPlanDigestWithoutConsumingPlan() throws Exception {
    HopCorrectionPlanManager manager = manager();
    Map<String, Object> prepared = manager.prepare("digest.hwf", "workflow", workflowOperations());

    assertThrows(
        SecurityException.class,
        () -> manager.apply(String.valueOf(prepared.get("plan_id")), "0".repeat(64)));
    assertEquals("prepared", manager.status(String.valueOf(prepared.get("plan_id"))).get("state"));
    assertFalse(Files.exists(project.resolve("digest.hwf")));
  }

  @Test
  void explainsMalformedAndMismatchedPlanDigestsWithoutConsumingPlan() throws Exception {
    HopCorrectionPlanManager manager = manager();
    Map<String, Object> prepared =
        manager.prepare("digest-diagnostics.hwf", "workflow", workflowOperations());
    String planId = String.valueOf(prepared.get("plan_id"));

    SecurityException malformed =
        assertThrows(SecurityException.class, () -> manager.apply(planId, "not-a-sha"));
    assertTrue(malformed.getMessage().contains("64 hexadecimal"));
    assertEquals("prepared", manager.status(planId).get("state"));

    SecurityException mismatch =
        assertThrows(SecurityException.class, () -> manager.apply(planId, "0".repeat(64)));
    assertEquals("Correction plan SHA-256 does not match", mismatch.getMessage());
    assertFalse(mismatch.getMessage().contains(String.valueOf(prepared.get("plan_sha256"))));
    assertEquals("prepared", manager.status(planId).get("state"));

    Map<String, Object> applied =
        manager.apply(planId, String.valueOf(prepared.get("plan_sha256")).toUpperCase());
    assertEquals("applied", applied.get("state"));
  }

  @Test
  void bindsExistingDefinitionShaAndAuditsStalePlanFailure() throws Exception {
    HopDefinitionMutator mutator = mutator();
    Map<String, Object> created =
        mutator.mutate("stale.hwf", "workflow", workflowOperations(), null, true);
    HopCorrectionPlanManager manager = new HopCorrectionPlanManager(mutator);
    Map<String, Object> prepared =
        manager.prepare(
            "stale.hwf",
            "workflow",
            List.of(Map.of("operation", "set_description", "value", "planned")));
    assertEquals(created.get("new_sha256"), prepared.get("bound_sha256"));

    mutator.mutate(
        "stale.hwf",
        "workflow",
        List.of(Map.of("operation", "set_description", "value", "concurrent")),
        String.valueOf(created.get("new_sha256")),
        true);

    assertThrows(
        Exception.class,
        () ->
            manager.apply(
                String.valueOf(prepared.get("plan_id")),
                String.valueOf(prepared.get("plan_sha256"))));
    Map<String, Object> status = manager.status(String.valueOf(prepared.get("plan_id")));
    assertEquals("failed", status.get("state"));
    assertEquals(2, ((List<?>) status.get("audit")).size());
  }

  @Test
  void serviceRequiresMutationOptInForCorrectionPlans() throws Exception {
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
      assertThrows(
          SecurityException.class,
          () -> service.prepareCorrectionPlan("guarded.hwf", "workflow", workflowOperations()));
      assertThrows(SecurityException.class, () -> service.correctionPlanStatus("missing-plan"));
      assertThrows(
          SecurityException.class,
          () -> service.applyCorrectionPlan("missing-plan", "0".repeat(64)));
      assertFalse(Files.exists(project.resolve("guarded.hwf")));
    } finally {
      service.close();
    }
  }

  private HopCorrectionPlanManager manager() throws Exception {
    return new HopCorrectionPlanManager(mutator());
  }

  private HopDefinitionMutator mutator() throws Exception {
    return new HopDefinitionMutator(
        new ProjectFiles(project), new Variables(), new MemoryMetadataProvider());
  }

  private static List<Map<String, Object>> workflowOperations() {
    Map<String, Object> start = new LinkedHashMap<>();
    start.put("operation", "add_component");
    start.put("plugin_id", "SPECIAL");
    start.put("name", "Start");
    start.put("properties", Map.of("repeat", false));
    return List.of(
        start,
        Map.of("operation", "add_component", "plugin_id", "DUMMY", "name", "Finish"),
        Map.of("operation", "add_hop", "from", "Start", "to", "Finish", "unconditional", true));
  }
}
