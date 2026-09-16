package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.apache.hop.core.ICheckResult;
import org.junit.jupiter.api.Test;

class HopDefinitionTestReportTest {
  @Test
  void blocksLaterPhasesAndSuggestsSemanticRepairWhenStructureIsInvalid() {
    Map<String, Object> report =
        HopDefinitionTestReport.build(
            "broken.hpl",
            Map.of(
                "valid", false,
                "errors", List.of("Duplicate component name: Input"),
                "warnings", List.of("transform without name")),
            true,
            null,
            true,
            null,
            null,
            "structural_validation_failed");

    assertFalse((Boolean) report.get("passed"));
    assertEquals(2, report.get("diagnostic_count"));
    Map<?, ?> phases = (Map<?, ?>) report.get("phases");
    assertEquals(
        "structural_validation_failed",
        ((Map<?, ?>) phases.get("deep_check")).get("skipped_reason"));
    assertEquals(
        "structural_validation_failed",
        ((Map<?, ?>) phases.get("execution")).get("skipped_reason"));
    Map<?, ?> suggestion = (Map<?, ?>) ((List<?>) report.get("suggestions")).get(0);
    assertEquals("hop_mutate_definition", suggestion.get("tool"));
    assertEquals(false, suggestion.get("auto_applied"));
  }

  @Test
  void normalizesDeepAndExecutionFailuresWithBoundedRedactedLogs() {
    Map<String, Object> report =
        HopDefinitionTestReport.build(
            "failed.hpl",
            Map.of("valid", true, "errors", List.of(), "warnings", List.of()),
            true,
            Map.of(
                "valid",
                false,
                "issues",
                List.of(
                    Map.of(
                        "type",
                        ICheckResult.TYPE_RESULT_ERROR,
                        "text",
                        "Required field is missing"))),
            true,
            Map.of(
                "ok",
                false,
                "timed_out",
                false,
                "error_count",
                2,
                "status",
                "stopped",
                "log_channel_id",
                "channel"),
            Map.of(
                "events",
                List.of(
                    Map.of(
                        "level", "ERROR",
                        "message", "password=must-not-leak"))),
            "");

    assertFalse((Boolean) report.get("passed"));
    assertEquals(3, report.get("diagnostic_count"));
    List<?> diagnostics = (List<?>) report.get("diagnostics");
    assertTrue(
        diagnostics.stream()
            .map(Map.class::cast)
            .anyMatch(item -> "EXECUTION_FAILED".equals(item.get("code"))));
    assertTrue(
        diagnostics.stream()
            .map(Map.class::cast)
            .map(item -> String.valueOf(item.get("message")))
            .noneMatch(message -> message.contains("must-not-leak")));
  }

  @Test
  void reportsSuccessWhenRequestedPhasesPass() {
    Map<String, Object> report =
        HopDefinitionTestReport.build(
            "ok.hpl",
            Map.of("valid", true, "errors", List.of(), "warnings", List.of()),
            false,
            null,
            true,
            Map.of("ok", true, "timed_out", false),
            Map.of("events", List.of()),
            "");

    assertTrue((Boolean) report.get("passed"));
    assertEquals(0, report.get("diagnostic_count"));
    Map<?, ?> phases = (Map<?, ?>) report.get("phases");
    assertEquals(true, ((Map<?, ?>) phases.get("deep_check")).get("passed"));
    assertEquals(true, ((Map<?, ?>) phases.get("execution")).get("performed"));
  }
}
