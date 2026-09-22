package io.github.michaaels.hop.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Keeps bounded, immutable, single-use semantic correction plans for one MCP session. */
final class HopCorrectionPlanManager {
  static final int MAX_PLANS = 100;
  static final Duration PLAN_TTL = Duration.ofHours(1);
  private static final int MAX_AUDIT_EVENTS = 10;

  private final HopDefinitionMutator mutator;
  private final LinkedHashMap<String, Plan> plans = new LinkedHashMap<>();

  HopCorrectionPlanManager(HopDefinitionMutator mutator) {
    this.mutator = mutator;
  }

  synchronized Map<String, Object> prepare(
      String path, String kind, List<Map<String, Object>> operations) throws Exception {
    expirePlans();
    List<Map<String, Object>> frozenOperations = freezeOperations(operations);
    Map<String, Object> preview = mutator.mutate(path, kind, frozenOperations, null, false);
    boolean targetExists = Boolean.TRUE.equals(preview.get("target_exists"));
    String boundSha256 = targetExists ? String.valueOf(preview.get("old_sha256")) : "";
    String normalizedPath = String.valueOf(preview.get("path"));
    String normalizedKind = String.valueOf(preview.get("kind"));
    Instant createdAt = Instant.now();
    String planId = UUID.randomUUID().toString();
    String planSha256 =
        digest(normalizedPath, normalizedKind, boundSha256, frozenOperations, createdAt.toString());
    Plan plan =
        new Plan(
            planId,
            planSha256,
            normalizedPath,
            normalizedKind,
            boundSha256,
            targetExists,
            frozenOperations,
            preview,
            createdAt,
            createdAt.plus(PLAN_TTL));
    plan.audit("prepared", "Semantic preview validated");
    retain(plan);
    return plan.preparedResult();
  }

  synchronized Map<String, Object> apply(String planId, String planSha256) throws Exception {
    expirePlans();
    Plan plan = requirePlan(planId);
    plan.requirePrepared();
    String suppliedSha256 = required(planSha256, "plan_sha256").trim().toLowerCase(Locale.ROOT);
    if (!suppliedSha256.matches("[0-9a-f]{64}")) {
      throw McpException.validation(
          "INVALID_PLAN_SHA256", "plan_sha256 must contain exactly 64 hexadecimal characters");
    }
    if (!MessageDigest.isEqual(
        plan.planSha256.getBytes(StandardCharsets.US_ASCII),
        suppliedSha256.getBytes(StandardCharsets.US_ASCII))) {
      throw McpException.precondition(
          "PLAN_DIGEST_MISMATCH",
          "Correction plan digest does not match; read the plan status and retry with its current digest.",
          false);
    }
    try {
      Map<String, Object> mutation =
          mutator.mutate(
              plan.path,
              plan.kind,
              plan.operations,
              plan.targetExists ? plan.boundSha256 : null,
              true);
      plan.state = "applied";
      plan.audit("applied", "Correction plan applied through native semantic mutation");
      Map<String, Object> result = new LinkedHashMap<>(plan.summary());
      result.put("mutation", mutation);
      return result;
    } catch (Exception failure) {
      plan.state = "failed";
      plan.audit("failed", HopXml.redact(String.valueOf(failure.getMessage())));
      throw failure;
    }
  }

  synchronized Map<String, Object> status(String planId) {
    expirePlans();
    return requirePlan(planId).statusResult();
  }

  private void retain(Plan plan) {
    while (plans.size() >= MAX_PLANS) {
      plans.remove(plans.keySet().iterator().next());
    }
    plans.put(plan.planId, plan);
  }

  private void expirePlans() {
    Instant now = Instant.now();
    plans.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt));
  }

  private Plan requirePlan(String planId) {
    Plan plan = plans.get(required(planId, "plan_id"));
    if (plan == null) {
      throw new IllegalArgumentException("Unknown or expired correction plan");
    }
    return plan;
  }

  private static List<Map<String, Object>> freezeOperations(List<Map<String, Object>> operations) {
    if (operations == null) return List.of();
    List<Map<String, Object>> result = new ArrayList<>(operations.size());
    for (Map<String, Object> operation : operations) {
      if (operation == null) throw new IllegalArgumentException("Correction operation is required");
      result.add(freezeMap(operation));
    }
    return List.copyOf(result);
  }

  private static Map<String, Object> freezeMap(Map<?, ?> source) {
    Map<String, Object> result = new LinkedHashMap<>();
    source.entrySet().stream()
        .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
        .forEach(
            entry -> result.put(String.valueOf(entry.getKey()), freezeValue(entry.getValue())));
    return Collections.unmodifiableMap(result);
  }

  private static Object freezeValue(Object value) {
    if (value instanceof Map<?, ?> map) return freezeMap(map);
    if (value instanceof List<?> list)
      return list.stream().map(HopCorrectionPlanManager::freezeValue).toList();
    if (value == null
        || value instanceof String
        || value instanceof Number
        || value instanceof Boolean) return value;
    throw new IllegalArgumentException(
        "Unsupported correction plan value type: " + value.getClass().getSimpleName());
  }

  private static String digest(
      String path,
      String kind,
      String expectedSha256,
      List<Map<String, Object>> operations,
      String createdAt)
      throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    canonical(digest, List.of(path, kind, expectedSha256, operations, createdAt));
    return java.util.HexFormat.of().formatHex(digest.digest());
  }

  private static void canonical(MessageDigest digest, Object value) {
    if (value instanceof Map<?, ?> map) {
      update(digest, "{");
      map.entrySet().stream()
          .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
          .forEach(
              entry -> {
                canonical(digest, String.valueOf(entry.getKey()));
                canonical(digest, entry.getValue());
              });
      update(digest, "}");
    } else if (value instanceof List<?> list) {
      update(digest, "[");
      list.forEach(item -> canonical(digest, item));
      update(digest, "]");
    } else {
      update(
          digest,
          value == null
              ? "null"
              : value.getClass().getName() + ":" + String.valueOf(value).length() + ":" + value);
    }
  }

  private static void update(MessageDigest digest, String value) {
    digest.update(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String required(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    return value;
  }

  private static final class Plan {
    private final String planId;
    private final String planSha256;
    private final String path;
    private final String kind;
    private final String boundSha256;
    private final boolean targetExists;
    private final List<Map<String, Object>> operations;
    private final Map<String, Object> preview;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final List<Map<String, Object>> audit = new ArrayList<>();
    private String state = "prepared";

    private Plan(
        String planId,
        String planSha256,
        String path,
        String kind,
        String boundSha256,
        boolean targetExists,
        List<Map<String, Object>> operations,
        Map<String, Object> preview,
        Instant createdAt,
        Instant expiresAt) {
      this.planId = planId;
      this.planSha256 = planSha256;
      this.path = path;
      this.kind = kind;
      this.boundSha256 = boundSha256;
      this.targetExists = targetExists;
      this.operations = operations;
      this.preview = Collections.unmodifiableMap(new LinkedHashMap<>(preview));
      this.createdAt = createdAt;
      this.expiresAt = expiresAt;
    }

    private void requirePrepared() throws McpException {
      if (!"prepared".equals(state)) {
        throw McpException.conflict(
            "CORRECTION_PLAN_NOT_PREPARED",
            "Correction plan is single-use and is already " + state + "; prepare a new plan.");
      }
    }

    private void audit(String event, String message) {
      if (audit.size() >= MAX_AUDIT_EVENTS) return;
      audit.add(
          Map.of(
              "timestamp",
              Instant.now().toString(),
              "event",
              event,
              "message",
              message == null ? "" : message));
    }

    private Map<String, Object> preparedResult() {
      Map<String, Object> result = new LinkedHashMap<>(summary());
      result.put("preview", preview);
      return result;
    }

    private Map<String, Object> statusResult() {
      Map<String, Object> result = new LinkedHashMap<>(summary());
      result.put("audit", List.copyOf(audit));
      return result;
    }

    private Map<String, Object> summary() {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("plan_id", planId);
      result.put("plan_sha256", planSha256);
      result.put("state", state);
      result.put("path", path);
      result.put("kind", kind);
      result.put("target_exists", targetExists);
      result.put("bound_sha256", boundSha256);
      result.put("operation_count", operations.size());
      result.put("created_at", createdAt.toString());
      result.put("expires_at", expiresAt.toString());
      result.put("single_use", true);
      result.put("auto_apply", false);
      return result;
    }
  }
}
