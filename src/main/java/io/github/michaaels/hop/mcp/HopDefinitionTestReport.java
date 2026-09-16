package io.github.michaaels.hop.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.hop.core.ICheckResult;

/** Builds a bounded, machine-readable report for the validate/check/execute test cycle. */
final class HopDefinitionTestReport {
  static final int MAX_DIAGNOSTICS = 500;

  private HopDefinitionTestReport() {}

  static Map<String, Object> build(
      String path,
      Map<String, Object> structural,
      boolean deepRequested,
      Map<String, Object> deep,
      boolean executionRequested,
      Map<String, Object> execution,
      Map<String, Object> logs,
      String skippedReason) {
    List<Map<String, Object>> diagnostics = new ArrayList<>();
    addMessages(diagnostics, "structural", "error", "STRUCTURAL_ERROR", structural.get("errors"));
    addMessages(
        diagnostics, "structural", "warning", "STRUCTURAL_WARNING", structural.get("warnings"));
    addDeepDiagnostics(diagnostics, deep);
    addExecutionDiagnostics(diagnostics, execution, logs);

    boolean structuralValid = booleanValue(structural.get("valid"));
    boolean deepPerformed = deep != null;
    boolean deepValid = !deepRequested || (deepPerformed && booleanValue(deep.get("valid")));
    boolean executionPerformed = execution != null;
    boolean executionValid =
        !executionRequested || (executionPerformed && booleanValue(execution.get("ok")));
    boolean passed = structuralValid && deepValid && executionValid;

    Map<String, Object> phases = new LinkedHashMap<>();
    phases.put("structural_validation", phase(true, true, structuralValid, ""));
    phases.put(
        "deep_check",
        phase(
            deepRequested,
            deepPerformed,
            deepValid,
            deepRequested && !deepPerformed ? skippedReason : ""));
    phases.put(
        "execution",
        phase(
            executionRequested,
            executionPerformed,
            executionValid,
            executionRequested && !executionPerformed ? skippedReason : ""));

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("path", path);
    result.put("passed", passed);
    result.put("correction_mode", "advisory_only");
    result.put("corrections_applied", false);
    result.put("phases", phases);
    result.put("structural_validation", structural);
    if (deep != null) result.put("deep_check", deep);
    if (execution != null) result.put("execution", execution);
    if (logs != null) result.put("execution_logs", logs);
    result.put("diagnostic_count", diagnostics.size());
    result.put("diagnostics", diagnostics);
    result.put("suggestions", suggestions(structuralValid, deepRequested, deep, execution));
    return result;
  }

  private static Map<String, Object> phase(
      boolean requested, boolean performed, boolean valid, String reason) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("requested", requested);
    result.put("performed", performed);
    result.put("passed", !requested || (performed && valid));
    if (!performed && reason != null && !reason.isBlank()) result.put("skipped_reason", reason);
    return result;
  }

  private static void addMessages(
      List<Map<String, Object>> diagnostics,
      String phase,
      String severity,
      String code,
      Object rawMessages) {
    if (!(rawMessages instanceof List<?> messages)) return;
    for (Object message : messages) {
      addDiagnostic(diagnostics, phase, severity, code, String.valueOf(message));
    }
  }

  private static void addDeepDiagnostics(
      List<Map<String, Object>> diagnostics, Map<String, Object> deep) {
    if (deep == null || !(deep.get("issues") instanceof List<?> issues)) return;
    for (Object value : issues) {
      if (!(value instanceof Map<?, ?> issue)) continue;
      int type = intValue(issue.get("type"));
      String severity =
          type == ICheckResult.TYPE_RESULT_ERROR
              ? "error"
              : type == ICheckResult.TYPE_RESULT_WARNING ? "warning" : "info";
      addDiagnostic(
          diagnostics, "deep_check", severity, "HOP_CHECK_" + type, mapValue(issue, "text"));
    }
  }

  private static void addExecutionDiagnostics(
      List<Map<String, Object>> diagnostics,
      Map<String, Object> execution,
      Map<String, Object> logs) {
    if (execution == null) return;
    boolean timedOut = booleanValue(execution.get("timed_out"));
    boolean ok = booleanValue(execution.get("ok"));
    if (timedOut) {
      addDiagnostic(
          diagnostics,
          "execution",
          "error",
          "EXECUTION_TIMEOUT",
          "Execution exceeded the configured timeout");
    } else if (!ok) {
      addDiagnostic(
          diagnostics,
          "execution",
          "error",
          "EXECUTION_FAILED",
          "Execution failed with "
              + longValue(execution.get("error_count"))
              + " errors; status="
              + String.valueOf(execution.getOrDefault("status", "")));
    }
    if (logs == null || !(logs.get("events") instanceof List<?> events)) return;
    for (Object value : events) {
      if (!(value instanceof Map<?, ?> event)) continue;
      String level = mapValue(event, "level");
      if ("ERROR".equalsIgnoreCase(level) || "FATAL".equalsIgnoreCase(level)) {
        addDiagnostic(
            diagnostics,
            "execution_log",
            "error",
            "EXECUTION_LOG_ERROR",
            mapValue(event, "message"));
      }
    }
  }

  private static void addDiagnostic(
      List<Map<String, Object>> diagnostics,
      String phase,
      String severity,
      String code,
      String message) {
    if (diagnostics.size() >= MAX_DIAGNOSTICS) return;
    diagnostics.add(
        Map.of(
            "phase", phase,
            "severity", severity,
            "code", code,
            "message", HopXml.redact(message)));
  }

  private static List<Map<String, Object>> suggestions(
      boolean structuralValid,
      boolean deepRequested,
      Map<String, Object> deep,
      Map<String, Object> execution) {
    List<Map<String, Object>> suggestions = new ArrayList<>();
    if (!structuralValid) {
      suggestions.add(
          suggestion(
              "repair_structure",
              "Resolve duplicate/missing components or invalid hops before execution",
              "hop_mutate_definition",
              List.of("rename_component", "remove_component", "remove_hop", "add_component")));
    }
    if (deepRequested && deep != null && !booleanValue(deep.get("valid"))) {
      suggestions.add(
          suggestion(
              "repair_component_configuration",
              "Inspect the failing component schema and preview a semantic property update",
              "hop_mutate_definition",
              List.of("update_component")));
    }
    if (execution != null && !booleanValue(execution.get("ok"))) {
      suggestions.add(
          suggestion(
              "inspect_execution_logs",
              "Review the bounded redacted execution logs before proposing a correction",
              "hop_logs",
              List.of()));
    }
    return suggestions;
  }

  private static Map<String, Object> suggestion(
      String code, String reason, String tool, List<String> operationCandidates) {
    return Map.of(
        "code", code,
        "reason", reason,
        "tool", tool,
        "operation_candidates", operationCandidates,
        "requires_preview", true,
        "auto_applied", false);
  }

  private static boolean booleanValue(Object value) {
    return value instanceof Boolean bool && bool;
  }

  private static int intValue(Object value) {
    return value instanceof Number number ? number.intValue() : 0;
  }

  private static long longValue(Object value) {
    return value instanceof Number number ? number.longValue() : 0;
  }

  private static String mapValue(Map<?, ?> map, String key) {
    Object value = map.get(key);
    return value == null ? "" : String.valueOf(value);
  }
}
