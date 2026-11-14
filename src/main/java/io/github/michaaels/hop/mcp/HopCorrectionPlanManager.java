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
  private final HopSemanticEventSink eventSink;
  private final LinkedHashMap<String, Plan> plans = new LinkedHashMap<>();

  HopCorrectionPlanManager(HopDefinitionMutator mutator) {
    this(mutator, HopSemanticEventSink.NONE);
  }

  HopCorrectionPlanManager(HopDefinitionMutator mutator, HopSemanticEventSink eventSink) {
    this.mutator = mutator;
    this.eventSink = eventSink;
  }

  synchronized Map<String, Object> prepare(
      String path, String kind, List<Map<String, Object>> operations) throws Exception {
    return prepare(path, kind, operations, null, null);
  }

  synchronized Map<String, Object> prepare(
      String path,
      String kind,
      List<Map<String, Object>> operations,
      String presentation,
      String previousPlanId)
      throws Exception {
    expirePlans();
    Plan previous = previousPlanId == null ? null : requirePlan(previousPlanId);
    List<Map<String, Object>> combined = new ArrayList<>();
    if (previous != null) {
      previous.requirePrepared();
      if (!previous.path.equals(path.replace('\\', '/'))
          || kind != null && !previous.kind.equals(kind))
        throw new IllegalArgumentException("A draft revision must use the same definition");
      combined.addAll(previous.operations);
    }
    if (operations != null) combined.addAll(operations);
    if (combined.size() > HopDefinitionMutator.MAX_OPERATIONS)
      throw new IllegalArgumentException("Combined draft exceeds 50 operations");
    String mode =
        HopGuidedPresentation.mode(
            presentation == null ? previous == null ? "instant" : previous.viewMode : presentation);
    List<Map<String, Object>> frozenOperations = freezeOperations(combined);
    Map<String, Object> preview = mutator.mutate(path, kind, frozenOperations, null, false);
    boolean targetExists = Boolean.TRUE.equals(preview.get("target_exists"));
    String boundSha256 = targetExists ? String.valueOf(preview.get("old_sha256")) : "";
    String normalizedPath = String.valueOf(preview.get("path"));
    String normalizedKind = String.valueOf(preview.get("kind"));
    if (previous != null
        && (previous.targetExists != targetExists || !previous.boundSha256.equals(boundSha256)))
      throw McpException.precondition(
          "STALE_DRAFT",
          "The definition changed while preparing the draft; prepare a new plan.",
          false);
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
    plan.viewMode = mode;
    if (mode.equals("guided") || previous != null && previous.presentation != null) {
      var draft =
          mutator.visualPreview(
              path, normalizedKind, frozenOperations, String.valueOf(preview.get("old_sha256")));
      var prior = previous == null ? null : previous.presentation;
      plan.presentation =
          new HopGuidedPresentation(
              prior == null ? planId : prior.id(),
              prior == null ? 1 : prior.revision() + 1,
              System.currentTimeMillis(),
              normalizedPath,
              normalizedKind,
              mode,
              "prepared",
              draft.baseline(),
              draft.result(),
              HopGuidedPresentation.steps(frozenOperations),
              ProjectFiles.sha256(draft.nativeDraft().encode()));
      plan.uiNotified = eventSink.publishPresentation(plan.presentation, draft.nativeDraft());
    }
    if (previous != null) {
      previous.state = "superseded";
      previous.audit("superseded", "A newer immutable draft revision was prepared");
    }
    retain(plan);
    return plan.preparedResult();
  }

  synchronized Map<String, Object> apply(String planId, String planSha256) throws Exception {
    return apply(planId, planSha256, null);
  }

  synchronized Map<String, Object> apply(String planId, String planSha256, String presentation)
      throws Exception {
    expirePlans();
    Plan plan = requirePlan(planId);
    plan.requirePrepared();
    String mode = HopGuidedPresentation.mode(presentation == null ? plan.viewMode : presentation);
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
      if (mode.equals("guided") && plan.presentation == null) {
        var draft =
            mutator.visualPreview(
                plan.path,
                plan.kind,
                plan.operations,
                String.valueOf(plan.preview.get("old_sha256")));
        plan.presentation =
            new HopGuidedPresentation(
                plan.planId,
                1,
                System.currentTimeMillis(),
                plan.path,
                plan.kind,
                mode,
                "prepared",
                draft.baseline(),
                draft.result(),
                HopGuidedPresentation.steps(plan.operations),
                ProjectFiles.sha256(draft.nativeDraft().encode()));
        eventSink.publishPresentation(plan.presentation, draft.nativeDraft());
      }
      plan.viewMode = mode;
      publish(plan, "applying");
      Map<String, Object> mutation =
          mutator.mutate(
              plan.path,
              plan.kind,
              plan.operations,
              plan.targetExists ? plan.boundSha256 : null,
              true);
      plan.state = "applied";
      plan.audit("applied", "Correction plan applied through native semantic mutation");
      plan.uiNotified = publish(plan);
      Map<String, Object> result = new LinkedHashMap<>(plan.summary());
      result.put("mutation", mutation);
      return result;
    } catch (Exception failure) {
      plan.state = "failed";
      plan.audit("failed", HopXml.redact(String.valueOf(failure.getMessage())));
      plan.uiNotified = publish(plan);
      throw failure;
    }
  }

  synchronized Map<String, Object> status(String planId) {
    expirePlans();
    return requirePlan(planId).statusResult();
  }

  synchronized Map<String, Object> status(String planId, boolean cancel) throws McpException {
    expirePlans();
    Plan plan = requirePlan(planId);
    if (cancel) {
      plan.requirePrepared();
      plan.state = "cancelled";
      plan.audit("cancelled", "Draft cancelled before application");
      plan.uiNotified = publish(plan);
    }
    return plan.statusResult();
  }

  private boolean publish(Plan plan) {
    return publish(plan, plan.state);
  }

  private boolean publish(Plan plan, String state) {
    if (plan.presentation == null) return false;
    var old = plan.presentation;
    plan.presentation =
        new HopGuidedPresentation(
            old.id(),
            old.revision(),
            System.currentTimeMillis(),
            old.path(),
            old.kind(),
            plan.viewMode,
            state,
            old.baseline(),
            old.result(),
            old.steps(),
            old.snapshotSha256());
    try {
      return eventSink.publishPresentation(plan.presentation);
    } catch (RuntimeException ignored) {
      return false;
    }
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
    private String viewMode = "instant";
    private HopGuidedPresentation presentation;
    private boolean uiNotified;

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
      result.put(
          "presentation",
          Map.of(
              "mode",
              viewMode,
              "async",
              true,
              "ui_notified",
              uiNotified,
              "presentation_id",
              presentation == null ? planId : presentation.id(),
              "revision",
              presentation == null ? 1 : presentation.revision()));
      return result;
    }
  }
}
