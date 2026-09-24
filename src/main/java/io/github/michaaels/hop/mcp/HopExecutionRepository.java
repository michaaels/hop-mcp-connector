package io.github.michaaels.hop.mcp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.core.row.RowBuffer;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.execution.Execution;
import org.apache.hop.execution.ExecutionData;
import org.apache.hop.execution.ExecutionDataSetMeta;
import org.apache.hop.execution.ExecutionInfoLocation;
import org.apache.hop.execution.ExecutionState;
import org.apache.hop.execution.ExecutionStateComponentMetrics;
import org.apache.hop.execution.ExecutionType;
import org.apache.hop.execution.IExecutionInfoLocation;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.metadata.api.IHopMetadataSerializer;

/** Reads execution history and metrics from Hop's configured native execution information store. */
@SuppressWarnings("unchecked")
final class HopExecutionRepository {
  static final int MAX_HISTORY_SCAN = 1_000;
  static final int MAX_HISTORY_RESULTS = 100;
  static final int MAX_CHILDREN_DEPTH = 32;
  static final int MAX_CHILDREN_NODES = 200;
  static final int MAX_COMPONENT_METRICS = 200;
  static final int MAX_METRIC_VALUES = 128;
  static final int MAX_CHILD_IDS = 200;
  static final int MAX_DETAILS = 128;
  static final int MAX_ERRORS = 50;
  static final int MAX_TEXT_LENGTH = 64 * 1024;
  static final int MAX_PROFILE_FIELDS = 128;
  static final int MAX_PROFILE_ROWS = 10_000;
  static final int MAX_PROFILE_SAMPLES = 20;
  static final int MAX_PROFILE_DISTINCT = 5_000;
  static final int MAX_PROFILE_DATA_SETS = 200;
  static final int MAX_LOCATION_LENGTH = 512;
  static final int MAX_FILTER_LENGTH = 512;

  @FunctionalInterface
  interface LocationAccess {
    Object with(String locationName, LocationAction action) throws Exception;
  }

  @FunctionalInterface
  interface LocationAction {
    Object apply(IExecutionInfoLocation location) throws Exception;
  }

  private record Child(Execution execution, int depth) {}

  private static final class FieldStats {
    private final Set<String> distinct = new LinkedHashSet<>();
    private final List<Object> samples = new ArrayList<>();
    private long observedRows;
    private long nulls;
    private boolean distinctTruncated;
    private BigDecimal numericSum;
    private BigDecimal numericMin;
    private BigDecimal numericMax;
    private String textMin;
    private String textMax;

    private FieldStats() {}

    private void accept(Object value) {
      observedRows++;
      if (value == null) {
        nulls++;
        return;
      }
      if (distinct.size() < MAX_PROFILE_DISTINCT) distinct.add(String.valueOf(value));
      else distinctTruncated = true;
      if (samples.size() < MAX_PROFILE_SAMPLES) samples.add(value);
      if (value instanceof Number number) {
        try {
          BigDecimal decimal = new BigDecimal(String.valueOf(number));
          numericSum = numericSum == null ? decimal : numericSum.add(decimal);
          numericMin =
              numericMin == null || decimal.compareTo(numericMin) < 0 ? decimal : numericMin;
          numericMax =
              numericMax == null || decimal.compareTo(numericMax) > 0 ? decimal : numericMax;
          return;
        } catch (NumberFormatException ignored) {
          // The native value is still safe to expose as a bounded sample.
        }
      }
      String text = safeText(String.valueOf(value), 512);
      if (textMin == null || text.compareTo(textMin) < 0) textMin = text;
      if (textMax == null || text.compareTo(textMax) > 0) textMax = text;
    }

    private Map<String, Object> toMap(boolean scanComplete) {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("available", observedRows > 0);
      result.put("observed_rows", observedRows);
      result.put("nulls", nulls);
      result.put("distinct", distinct.size());
      result.put("distinct_truncated", distinctTruncated);
      result.put("complete", scanComplete && !distinctTruncated);
      result.put("samples", samples);
      if (numericMin != null) {
        result.put("min", numberValue(numericMin));
        result.put("max", numberValue(numericMax));
        result.put(
            "average",
            numberValue(
                numericSum.divide(
                    BigDecimal.valueOf(observedRows - nulls), 8, RoundingMode.HALF_UP)));
      } else if (textMin != null) {
        result.put("min", textMin);
        result.put("max", textMax);
      }
      return result;
    }

    private static Object numberValue(BigDecimal value) {
      try {
        return value.stripTrailingZeros().scale() <= 0
            ? value.longValueExact()
            : value.doubleValue();
      } catch (ArithmeticException ignored) {
        return value.doubleValue();
      }
    }
  }

  private final ProjectFiles files;
  private final LocationAccess locationAccess;

  HopExecutionRepository(
      ProjectFiles files, IVariables variables, IHopMetadataProvider metadataProvider) {
    this(
        files,
        variables,
        metadataProvider,
        (locationName, action) ->
            withNativeLocation(locationName, variables, metadataProvider, action));
  }

  HopExecutionRepository(
      ProjectFiles files,
      IVariables variables,
      IHopMetadataProvider metadataProvider,
      LocationAccess locationAccess) {
    this.files = Objects.requireNonNull(files, "files");
    this.locationAccess = Objects.requireNonNull(locationAccess, "locationAccess");
  }

  Map<String, Object> history(
      String locationName,
      String path,
      String status,
      Long fromEpochMs,
      Long toEpochMs,
      int offset,
      int limit)
      throws Exception {
    validateLocation(locationName);
    validateFilter(path, "path");
    String normalizedStatus = normalizeStatus(status);
    validateEpochRange(fromEpochMs, toEpochMs);
    validatePage(offset, limit);

    return (Map<String, Object>)
        locationAccess.with(
            locationName,
            location -> {
              List<String> ids = location.getExecutionIds(false, MAX_HISTORY_SCAN + 1);
              boolean scanTruncated = ids.size() > MAX_HISTORY_SCAN;
              if (scanTruncated) ids = ids.subList(0, MAX_HISTORY_SCAN);

              List<Map<String, Object>> matches = new ArrayList<>();
              for (String id : ids) {
                Execution execution = location.getExecution(id);
                ExecutionState state =
                    execution == null ? null : location.getExecutionState(id, false);
                if (execution == null
                    || !matches(execution, state, path, normalizedStatus, fromEpochMs, toEpochMs)) {
                  continue;
                }
                matches.add(summary(execution, state));
              }
              matches.sort(
                  Comparator.comparingLong(
                          (Map<String, Object> row) ->
                              ((Number) row.get("start_epoch_ms")).longValue())
                      .reversed()
                      .thenComparing(row -> String.valueOf(row.get("execution_id"))));

              int from = Math.min(offset, matches.size());
              int to = Math.min(from + limit, matches.size());
              List<Map<String, Object>> page = new ArrayList<>(matches.subList(from, to));
              boolean hasMore = to < matches.size() || scanTruncated;
              Map<String, Object> result = new LinkedHashMap<>();
              result.put("location", locationName);
              result.put("path", path == null ? "" : path);
              result.put("status", normalizedStatus);
              result.put("from_epoch_ms", fromEpochMs == null ? 0L : fromEpochMs);
              result.put("to_epoch_ms", toEpochMs == null ? 0L : toEpochMs);
              result.put("offset", offset);
              result.put("limit", limit);
              result.put("count", matches.size());
              result.put("count_complete", !scanTruncated);
              result.put("returned", page.size());
              result.put("has_more", hasMore);
              result.put("scan_truncated", scanTruncated);
              result.put("executions", page);
              return result;
            });
  }

  Map<String, Object> detail(String locationName, String executionId) throws Exception {
    validateLocation(locationName);
    validateExecutionId(executionId);
    return (Map<String, Object>)
        locationAccess.with(
            locationName,
            location -> {
              Execution execution = location.getExecution(executionId);
              if (execution == null) {
                throw McpException.validation(
                    "EXECUTION_NOT_FOUND", "The native execution was not found.");
              }
              ExecutionState state = location.getExecutionState(executionId, false);
              Map<String, Object> result = new LinkedHashMap<>();
              result.put("location", locationName);
              result.put("execution", summary(execution, state));
              result.put("state", state == null ? stateRow(executionId) : stateRow(state));
              result.put(
                  "children_count",
                  state == null || state.getChildIds() == null
                      ? 0
                      : Math.min(MAX_CHILD_IDS, state.getChildIds().size()));
              result.put("metrics", state == null ? List.of() : metricRows(state.getMetrics()));
              result.put("details", state == null ? Map.of() : boundedDetails(state.getDetails()));
              result.put("errors", state == null ? List.of() : errorDetails(state));
              result.put("logging_available", false);
              return result;
            });
  }

  Map<String, Object> children(String locationName, String executionId, int maxDepth, int maxNodes)
      throws Exception {
    validateLocation(locationName);
    validateExecutionId(executionId);
    if (maxDepth < 1 || maxDepth > MAX_CHILDREN_DEPTH) {
      throw new IllegalArgumentException("max_depth must be between 1 and " + MAX_CHILDREN_DEPTH);
    }
    if (maxNodes < 1 || maxNodes > MAX_CHILDREN_NODES) {
      throw new IllegalArgumentException("max_nodes must be between 1 and " + MAX_CHILDREN_NODES);
    }

    return (Map<String, Object>)
        locationAccess.with(
            locationName,
            location -> {
              Execution root = location.getExecution(executionId);
              if (root == null) {
                throw McpException.validation(
                    "EXECUTION_NOT_FOUND", "The native execution was not found.");
              }
              Deque<Child> queue = new ArrayDeque<>();
              List<Map<String, Object>> rows = new ArrayList<>();
              java.util.Set<String> visited = new java.util.HashSet<>();
              visited.add(executionId);
              queue.add(new Child(root, 0));
              boolean truncated = false;
              while (!queue.isEmpty()) {
                Child current = queue.removeFirst();
                if (current.depth() >= maxDepth) {
                  continue;
                }
                List<Execution> children = location.findExecutions(current.execution().getId());
                for (Execution child : children) {
                  if (child == null || child.getId() == null || !visited.add(child.getId()))
                    continue;
                  if (rows.size() >= maxNodes) {
                    truncated = true;
                    break;
                  }
                  ExecutionState state = location.getExecutionState(child.getId(), false);
                  Map<String, Object> row = summary(child, state);
                  row.put("depth", current.depth() + 1);
                  rows.add(row);
                  queue.addLast(new Child(child, current.depth() + 1));
                }
                if (truncated) break;
              }
              if (!queue.isEmpty()) truncated = true;
              Map<String, Object> result = new LinkedHashMap<>();
              result.put("location", locationName);
              result.put("root_execution_id", executionId);
              result.put("max_depth", maxDepth);
              result.put("max_nodes", maxNodes);
              result.put("visited", visited.size());
              result.put("returned", rows.size());
              result.put("truncated", truncated);
              result.put("children", rows);
              return result;
            });
  }

  Map<String, Object> metrics(String locationName, String executionId) throws Exception {
    validateLocation(locationName);
    validateExecutionId(executionId);
    return (Map<String, Object>)
        locationAccess.with(
            locationName,
            location -> {
              Execution execution = location.getExecution(executionId);
              if (execution == null) {
                throw McpException.validation(
                    "EXECUTION_NOT_FOUND", "The native execution was not found.");
              }
              ExecutionState state = location.getExecutionState(executionId, false);
              List<Map<String, Object>> rows =
                  state == null ? List.of() : metricRows(state.getMetrics());
              Map<String, Object> result = new LinkedHashMap<>();
              result.put("location", locationName);
              result.put("execution_id", executionId);
              result.put(
                  "available",
                  state != null && state.getMetrics() != null && !state.getMetrics().isEmpty());
              result.put("component_count", rows.size());
              result.put(
                  "truncated",
                  state != null
                      && state.getMetrics() != null
                      && state.getMetrics().size() > rows.size());
              result.put("components", rows);
              return result;
            });
  }

  Map<String, Object> profile(
      String locationName, String executionId, String transform, List<String> fields)
      throws Exception {
    validateLocation(locationName);
    validateExecutionId(executionId);
    if (transform != null && transform.length() > MAX_FILTER_LENGTH) {
      throw new IllegalArgumentException("transform exceeds " + MAX_FILTER_LENGTH + " characters");
    }
    if (fields == null || fields.isEmpty())
      throw new IllegalArgumentException("fields are required");
    if (fields.size() > MAX_PROFILE_FIELDS) {
      throw new IllegalArgumentException("fields cannot exceed " + MAX_PROFILE_FIELDS + " entries");
    }
    Map<String, FieldStats> stats = new LinkedHashMap<>();
    for (String field : fields) {
      if (field == null || field.isBlank() || field.length() > 512) {
        throw new IllegalArgumentException("field names must be non-empty and bounded");
      }
      if (stats.size() < MAX_PROFILE_FIELDS) stats.putIfAbsent(field, new FieldStats());
    }

    return (Map<String, Object>)
        locationAccess.with(
            locationName,
            location -> {
              if (location.getExecution(executionId) == null) {
                throw McpException.validation(
                    "EXECUTION_NOT_FOUND", "The native execution was not found.");
              }
              ExecutionData data = location.getExecutionData(executionId, null);
              long rowsScanned = 0L;
              int dataSetsScanned = 0;
              boolean rowsTruncated = false;
              if (data != null && data.getDataSets() != null) {
                for (Map.Entry<String, RowBuffer> entry : data.getDataSets().entrySet()) {
                  if (dataSetsScanned++ >= MAX_PROFILE_DATA_SETS) {
                    rowsTruncated = true;
                    break;
                  }
                  RowBuffer buffer = entry.getValue();
                  ExecutionDataSetMeta meta =
                      data.getSetMetaData() == null
                          ? null
                          : data.getSetMetaData().get(entry.getKey());
                  if (!matchesTransform(meta, transform)
                      || buffer == null
                      || buffer.getRowMeta() == null) continue;
                  IRowMeta rowMeta = buffer.getRowMeta();
                  List<Object[]> rows = buffer.getBuffer() == null ? List.of() : buffer.getBuffer();
                  Map<String, Integer> fieldIndexes = new LinkedHashMap<>();
                  for (String field : stats.keySet()) {
                    int index = rowMeta.indexOfValue(field);
                    if (index < 0
                        && meta != null
                        && field.equalsIgnoreCase(String.valueOf(meta.getFieldName()))
                        && rowMeta.size() == 1) {
                      index = 0;
                    }
                    if (index >= 0) fieldIndexes.put(field, index);
                  }
                  if (fieldIndexes.isEmpty()) continue;
                  for (Object[] row : rows) {
                    if (rowsScanned >= MAX_PROFILE_ROWS) {
                      rowsTruncated = true;
                      break;
                    }
                    rowsScanned++;
                    if (row == null) continue;
                    for (Map.Entry<String, Integer> fieldIndex : fieldIndexes.entrySet()) {
                      int index = fieldIndex.getValue();
                      if (index < row.length) stats.get(fieldIndex.getKey()).accept(row[index]);
                    }
                  }
                  if (rowsScanned >= MAX_PROFILE_ROWS) {
                    rowsTruncated = true;
                    break;
                  }
                }
              }
              Map<String, Object> fieldOutput = new LinkedHashMap<>();
              boolean profileAvailable = false;
              for (Map.Entry<String, FieldStats> field : stats.entrySet()) {
                Map<String, Object> value = field.getValue().toMap(!rowsTruncated);
                profileAvailable |= Boolean.TRUE.equals(value.get("available"));
                if (SensitiveData.isSensitiveKey(field.getKey())) {
                  value.remove("min");
                  value.remove("max");
                  value.remove("average");
                  value.put("samples", List.of());
                } else {
                  value.put("samples", SensitiveData.redactValue(value.get("samples")));
                }
                fieldOutput.put(safeText(field.getKey(), 512), value);
              }
              Map<String, Object> result = new LinkedHashMap<>();
              result.put("location", locationName);
              result.put("execution_id", executionId);
              result.put("transform", safeText(transform, MAX_FILTER_LENGTH));
              result.put("available", data != null && profileAvailable);
              result.put("source", "stored_execution_data");
              result.put("data_sets_scanned", dataSetsScanned);
              result.put("rows_scanned", rowsScanned);
              result.put("rows_truncated", rowsTruncated);
              result.put("fields", fieldOutput);
              return result;
            });
  }

  private boolean matches(
      Execution execution,
      ExecutionState state,
      String path,
      String status,
      Long fromEpochMs,
      Long toEpochMs) {
    String query = path == null ? "" : path.trim().toLowerCase(Locale.ROOT);
    if (!query.isBlank()) {
      String filename = safePath(execution.getFilename()).toLowerCase(Locale.ROOT);
      String name = safeText(execution.getName(), 512).toLowerCase(Locale.ROOT);
      if (!filename.contains(query) && !name.contains(query)) return false;
    }
    long start = epoch(execution.getExecutionStartDate());
    if (fromEpochMs != null && start < fromEpochMs) return false;
    if (toEpochMs != null && start > toEpochMs) return false;
    if (status.isBlank()) return true;
    return status.equals(statusOf(state));
  }

  private static boolean matchesTransform(ExecutionDataSetMeta metadata, String transform) {
    if (transform == null || transform.isBlank()) return true;
    return metadata != null
        && metadata.getName() != null
        && metadata.getName().equalsIgnoreCase(transform.trim());
  }

  private static Object withNativeLocation(
      String locationName,
      IVariables variables,
      IHopMetadataProvider metadataProvider,
      LocationAction action)
      throws Exception {
    IHopMetadataSerializer<ExecutionInfoLocation> serializer =
        metadataProvider.getSerializer(ExecutionInfoLocation.class);
    ExecutionInfoLocation metadata = serializer.load(locationName);
    if (metadata == null) {
      throw McpException.validation(
          "EXECUTION_LOCATION_NOT_FOUND",
          "The native execution information location was not found.");
    }
    IExecutionInfoLocation location = metadata.getExecutionInfoLocation();
    if (location == null) {
      throw McpException.validation(
          "EXECUTION_LOCATION_INVALID", "The native execution information location is invalid.");
    }
    Exception failure = null;
    try {
      location.initialize(variables, metadataProvider);
      return action.apply(location);
    } catch (Exception exception) {
      failure = exception;
      throw exception;
    } finally {
      try {
        location.close();
      } catch (Exception closeException) {
        if (failure == null) throw closeException;
      }
    }
  }

  private Map<String, Object> summary(Execution execution, ExecutionState state) {
    Map<String, Object> row = new LinkedHashMap<>();
    long start = epoch(execution.getExecutionStartDate());
    long end = state == null ? 0L : epoch(state.getExecutionEndDate());
    row.put("execution_id", safeText(execution.getId(), 256));
    row.put("path", safePath(execution.getFilename()));
    row.put("name", safeText(execution.getName(), 512));
    row.put("type", typeOf(execution.getExecutionType()));
    row.put("parent_id", safeText(execution.getParentId(), 256));
    row.put("run_configuration", safeText(execution.getRunConfigurationName(), 256));
    row.put("registration_epoch_ms", epoch(execution.getRegistrationDate()));
    row.put("start_epoch_ms", start);
    row.put("end_epoch_ms", end);
    row.put("duration_ms", end > 0L && start > 0L ? Math.max(0L, end - start) : 0L);
    row.put("status", statusOf(state));
    row.put("failed", state != null && state.isFailed());
    row.put("active", state != null && state.isRunning());
    return row;
  }

  private static Map<String, Object> stateRow(String executionId) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("execution_id", executionId);
    row.put("type", "");
    row.put("parent_id", "");
    row.put("name", "");
    row.put("status", "unavailable");
    row.put("status_description", "");
    row.put("failed", false);
    row.put("running", false);
    row.put("finished", false);
    row.put("end_epoch_ms", 0L);
    row.put("child_ids", List.of());
    return row;
  }

  private static Map<String, Object> stateRow(ExecutionState state) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("execution_id", safeText(state.getId(), 256));
    row.put("type", typeOf(state.getExecutionType()));
    row.put("parent_id", safeText(state.getParentId(), 256));
    row.put("name", safeText(state.getName(), 512));
    row.put("status", statusOf(state));
    row.put("status_description", safeText(state.getStatusDescription(), 512));
    row.put("failed", state.isFailed());
    row.put("running", state.isRunning());
    row.put("finished", state.isFinished());
    row.put("end_epoch_ms", epoch(state.getExecutionEndDate()));
    row.put("child_ids", boundedStrings(state.getChildIds(), MAX_CHILD_IDS));
    return row;
  }

  private static List<Map<String, Object>> metricRows(
      List<ExecutionStateComponentMetrics> metrics) {
    if (metrics == null || metrics.isEmpty()) return List.of();
    List<Map<String, Object>> rows = new ArrayList<>();
    int count = 0;
    for (ExecutionStateComponentMetrics metric : metrics) {
      if (count++ >= MAX_COMPONENT_METRICS) break;
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("component", safeText(metric == null ? null : metric.getComponentName(), 512));
      row.put("copy", safeText(metric == null ? null : metric.getComponentCopy(), 128));
      row.put("metrics", boundedMetrics(metric == null ? null : metric.getMetrics()));
      rows.add(row);
    }
    return rows;
  }

  private static Map<String, Object> boundedMetrics(Map<String, Long> metrics) {
    if (metrics == null || metrics.isEmpty()) return Map.of();
    Map<String, Object> result = new LinkedHashMap<>();
    metrics.keySet().stream()
        .filter(Objects::nonNull)
        .sorted()
        .limit(MAX_METRIC_VALUES)
        .forEach(key -> result.put(safeText(key, 256), metrics.get(key)));
    return result;
  }

  private static Map<String, Object> boundedDetails(Map<String, String> details) {
    if (details == null || details.isEmpty()) return Map.of();
    Map<String, Object> result = new LinkedHashMap<>();
    details.keySet().stream()
        .filter(Objects::nonNull)
        .sorted()
        .limit(MAX_DETAILS)
        .forEach(key -> result.put(safeText(key, 256), SensitiveData.redactText(details.get(key))));
    return result;
  }

  private static List<String> errorDetails(ExecutionState state) {
    if (state.getDetails() == null || state.getDetails().isEmpty()) return List.of();
    List<String> errors = new ArrayList<>();
    for (Map.Entry<String, String> entry : state.getDetails().entrySet()) {
      String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
      if (!key.contains("error") && !key.contains("exception") && !key.contains("failure")) {
        continue;
      }
      if (errors.size() >= MAX_ERRORS) break;
      errors.add(safeText(entry.getValue(), 1024));
    }
    return errors;
  }

  private String safePath(String filename) {
    if (filename == null || filename.isBlank()) return "";
    try {
      Path path = Path.of(filename);
      if (!path.isAbsolute()) {
        Path normalizedRelative = path.normalize();
        if (normalizedRelative.startsWith(Path.of(".."))) return "<outside-project>";
        return safeText(normalizedRelative.toString().replace('\\', '/'), 4096);
      }
      Path normalized = path.toAbsolutePath().normalize();
      if (normalized.startsWith(files.root())) return files.relative(normalized);
    } catch (RuntimeException ignored) {
      // Fall through to the protected marker.
    }
    return "<outside-project>";
  }

  private static String statusOf(ExecutionState state) {
    if (state == null) return "unavailable";
    if (state.isFailed()) return "failed";
    if (state.isRunning()) return "running";
    if (state.isFinished()) return "finished";
    String description = state.getStatusDescription();
    return description == null || description.isBlank()
        ? "unknown"
        : safeText(description, 256).toLowerCase(Locale.ROOT);
  }

  private static String typeOf(ExecutionType type) {
    return type == null ? "" : type.name().toLowerCase(Locale.ROOT);
  }

  private static long epoch(Date date) {
    return date == null ? 0L : Math.max(0L, date.getTime());
  }

  private static List<String> boundedStrings(List<String> values, int max) {
    if (values == null || values.isEmpty()) return List.of();
    List<String> result = new ArrayList<>();
    for (String value : values) {
      if (result.size() >= max) break;
      result.add(safeText(value, 256));
    }
    return result;
  }

  private static String safeText(String value, int max) {
    if (value == null) return "";
    String redacted = SensitiveData.redactText(value);
    if (redacted == null) return "";
    return redacted.length() <= max ? redacted : redacted.substring(0, max);
  }

  private static String normalizeStatus(String status) {
    String value = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
    if (!value.isBlank()
        && !List.of("running", "finished", "failed", "unknown", "unavailable").contains(value)) {
      throw new IllegalArgumentException("status must be running, finished, failed, or unknown");
    }
    return value;
  }

  private static void validateLocation(String locationName) {
    if (locationName == null || locationName.isBlank()) {
      throw new IllegalArgumentException("location is required");
    }
    if (locationName.length() > MAX_LOCATION_LENGTH) {
      throw new IllegalArgumentException("location exceeds " + MAX_LOCATION_LENGTH + " characters");
    }
  }

  private static void validateExecutionId(String executionId) {
    if (executionId == null || executionId.isBlank()) {
      throw new IllegalArgumentException("execution_id is required");
    }
    if (executionId.length() > 256) throw new IllegalArgumentException("execution_id is too long");
  }

  private static void validateFilter(String filter, String name) {
    if (filter != null && filter.length() > MAX_FILTER_LENGTH) {
      throw new IllegalArgumentException(name + " exceeds " + MAX_FILTER_LENGTH + " characters");
    }
  }

  private static void validateEpochRange(Long fromEpochMs, Long toEpochMs) {
    if (fromEpochMs != null && fromEpochMs < 0L)
      throw new IllegalArgumentException("from_epoch_ms must be non-negative");
    if (toEpochMs != null && toEpochMs < 0L)
      throw new IllegalArgumentException("to_epoch_ms must be non-negative");
    if (fromEpochMs != null && toEpochMs != null && fromEpochMs > toEpochMs) {
      throw new IllegalArgumentException("from_epoch_ms must not exceed to_epoch_ms");
    }
  }

  private static void validatePage(int offset, int limit) {
    if (offset < 0 || offset > MAX_HISTORY_SCAN) {
      throw new IllegalArgumentException("offset must be between 0 and " + MAX_HISTORY_SCAN);
    }
    if (limit < 1 || limit > MAX_HISTORY_RESULTS) {
      throw new IllegalArgumentException("limit must be between 1 and " + MAX_HISTORY_RESULTS);
    }
  }
}
