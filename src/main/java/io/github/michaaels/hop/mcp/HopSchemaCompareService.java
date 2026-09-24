package io.github.michaaels.hop.mcp;

import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.hop.core.Const;
import org.apache.hop.core.database.Database;
import org.apache.hop.core.database.DatabaseMeta;
import org.apache.hop.core.logging.LoggingObject;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.core.row.IValueMeta;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.metadata.api.IHopMetadataSerializer;

/** Explicitly authorized, bounded native table-schema comparison. */
final class HopSchemaCompareService {
  static final int DEFAULT_TIMEOUT_SECONDS = 30;
  static final int MAX_TIMEOUT_SECONDS = 60;
  static final int MAX_NAME_LENGTH = 512;
  static final int MAX_FIELD_TYPE_LENGTH = 256;
  static final int MAX_FIELDS = 200;
  static final int MAX_DIFFERENCES = MAX_FIELDS * 6;

  @FunctionalInterface
  interface SchemaLoader {
    SchemaReadResult load(
        DatabaseMeta connection, IVariables variables, String schema, String table)
        throws Exception;
  }

  @FunctionalInterface
  interface ConnectionLoader {
    DatabaseMeta load(String name) throws Exception;
  }

  record SchemaColumn(
      String name,
      String type,
      String originalTypeName,
      int length,
      int precision,
      int scale,
      Boolean nullable) {}

  record SchemaReadResult(List<SchemaColumn> columns, int totalCount, boolean truncated) {}

  private record ExpectedColumn(
      String name,
      String type,
      Integer length,
      Integer precision,
      Integer scale,
      Boolean nullable) {}

  private final IHopMetadataProvider metadataProvider;
  private final IVariables variables;
  private final boolean allowDeepCheck;
  private final SchemaLoader schemaLoader;
  private final ConnectionLoader connectionLoader;

  HopSchemaCompareService(
      IHopMetadataProvider metadataProvider, IVariables variables, boolean allowDeepCheck) {
    this(
        metadataProvider,
        variables,
        allowDeepCheck,
        HopSchemaCompareService::loadNativeSchema,
        null);
  }

  HopSchemaCompareService(
      IHopMetadataProvider metadataProvider,
      IVariables variables,
      boolean allowDeepCheck,
      SchemaLoader schemaLoader,
      ConnectionLoader connectionLoader) {
    this.metadataProvider = metadataProvider;
    this.variables = variables;
    this.allowDeepCheck = allowDeepCheck;
    this.schemaLoader =
        schemaLoader == null ? HopSchemaCompareService::loadNativeSchema : schemaLoader;
    this.connectionLoader =
        connectionLoader == null ? this::loadNativeConnection : connectionLoader;
  }

  Map<String, Object> compare(
      String connectionName,
      String schemaName,
      String tableName,
      List<Map<String, Object>> expectedValues,
      int timeoutSeconds)
      throws Exception {
    requireDeepCheck();
    requireText(connectionName, "connection", MAX_NAME_LENGTH);
    schemaName = schemaName == null ? "" : schemaName;
    requireOptionalText(schemaName, "schema", MAX_NAME_LENGTH);
    requireText(tableName, "table", MAX_NAME_LENGTH);
    validateTimeout(timeoutSeconds);
    List<ExpectedColumn> expected = parseExpected(expectedValues);
    DatabaseMeta connection = connectionLoader.load(connectionName);
    if (connection == null) {
      throw McpException.validation(
          "METADATA_NOT_FOUND", "The RDBMS metadata object was not found.");
    }
    SchemaReadResult actual = loadWithTimeout(connection, schemaName, tableName, timeoutSeconds);
    if (actual == null) {
      throw McpException.validation(
          "SCHEMA_UNAVAILABLE", "The native database did not return table metadata.");
    }
    return compareValues(connectionName, schemaName, tableName, expected, actual);
  }

  private Map<String, Object> compareValues(
      String connectionName,
      String schemaName,
      String tableName,
      List<ExpectedColumn> expected,
      SchemaReadResult actual) {
    List<SchemaColumn> rawActualColumns = actual.columns() == null ? List.of() : actual.columns();
    List<SchemaColumn> actualColumns =
        rawActualColumns.stream().filter(Objects::nonNull).limit(MAX_FIELDS).toList();
    boolean actualTruncated = actual.truncated() || rawActualColumns.size() > MAX_FIELDS;
    int actualCount = Math.max(0, Math.max(actual.totalCount(), rawActualColumns.size()));
    Map<String, ExpectedColumn> expectedByName = new LinkedHashMap<>();
    for (ExpectedColumn column : expected) {
      expectedByName.put(normalizeName(column.name()), column);
    }
    Map<String, SchemaColumn> actualByName = new LinkedHashMap<>();
    for (SchemaColumn column : actualColumns) {
      if (column != null && column.name() != null) {
        actualByName.putIfAbsent(normalizeName(column.name()), column);
      }
    }

    List<Map<String, Object>> differences = new ArrayList<>();
    int differenceCount = 0;
    boolean differenceTruncated = false;
    for (ExpectedColumn expectedColumn : expected) {
      String key = normalizeName(expectedColumn.name());
      SchemaColumn actualColumn = actualByName.get(key);
      if (actualColumn == null) {
        differenceCount++;
        differenceTruncated =
            addDifference(
                differences,
                differenceCount,
                difference(
                    "COLUMN_REMOVED", "column", expectedColumn.name(), expectedColumn, null));
        continue;
      }
      differenceCount =
          compareAttribute(
              differences,
              differenceCount,
              expectedColumn,
              actualColumn,
              "TYPE_CHANGED",
              "type",
              typeMatches(expectedColumn.type(), actualColumn));
      differenceCount =
          compareAttribute(
              differences,
              differenceCount,
              expectedColumn,
              actualColumn,
              "LENGTH_CHANGED",
              "length",
              expectedColumn.length() == null
                  || expectedColumn.length().intValue() == actualColumn.length());
      differenceCount =
          compareAttribute(
              differences,
              differenceCount,
              expectedColumn,
              actualColumn,
              "PRECISION_CHANGED",
              "precision",
              expectedColumn.precision() == null
                  || expectedColumn.precision().intValue() == actualColumn.precision());
      differenceCount =
          compareAttribute(
              differences,
              differenceCount,
              expectedColumn,
              actualColumn,
              "SCALE_CHANGED",
              "scale",
              expectedColumn.scale() == null
                  || expectedColumn.scale().intValue() == actualColumn.scale());
      differenceCount =
          compareAttribute(
              differences,
              differenceCount,
              expectedColumn,
              actualColumn,
              "NULLABILITY_CHANGED",
              "nullable",
              expectedColumn.nullable() == null
                  || Objects.equals(expectedColumn.nullable(), actualColumn.nullable()));
    }
    for (SchemaColumn actualColumn : actualColumns) {
      if (actualColumn == null || expectedByName.containsKey(normalizeName(actualColumn.name()))) {
        continue;
      }
      differenceCount++;
      differenceTruncated |=
          addDifference(
              differences,
              differenceCount,
              difference("COLUMN_ADDED", "column", actualColumn.name(), null, actualColumn));
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("type", "rdbms");
    result.put("connection", connectionName);
    result.put("schema", schemaName);
    result.put("table", tableName);
    result.put("status", "success");
    result.put("matches", differenceCount == 0 && !actualTruncated);
    result.put("expected_count", expected.size());
    result.put("actual_count", actualCount);
    result.put("actual_fields_returned", actualColumns.size());
    result.put("actual_truncated", actualTruncated);
    result.put("comparison_complete", !actualTruncated);
    result.put("difference_count", differenceCount);
    result.put("difference_count_complete", !differenceTruncated);
    result.put("differences", differences);
    result.put("expected_fields", expected.stream().map(this::expectedMap).toList());
    result.put("actual_fields", actualColumns.stream().map(this::actualMap).toList());
    result.put("redaction_applied", true);
    return result;
  }

  private int compareAttribute(
      List<Map<String, Object>> differences,
      int differenceCount,
      ExpectedColumn expected,
      SchemaColumn actual,
      String code,
      String attribute,
      boolean matches) {
    if (matches) return differenceCount;
    int next = differenceCount + 1;
    addDifference(
        differences, next, difference(code, attribute, expected.name(), expected, actual));
    return next;
  }

  private boolean addDifference(
      List<Map<String, Object>> differences, int differenceCount, Map<String, Object> value) {
    if (differenceCount <= MAX_DIFFERENCES) {
      differences.add(value);
      return false;
    }
    return true;
  }

  private Map<String, Object> difference(
      String code,
      String attribute,
      String columnName,
      ExpectedColumn expected,
      SchemaColumn actual) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("code", code);
    result.put("attribute", attribute);
    result.put("column", safeName(columnName));
    result.put("expected", expected == null ? null : expectedMap(expected));
    result.put("actual", actual == null ? null : actualMap(actual));
    return result;
  }

  private Map<String, Object> expectedMap(ExpectedColumn column) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("name", safeName(column.name()));
    result.put("type", bounded(column.type(), MAX_FIELD_TYPE_LENGTH));
    result.put("original_type_name", "");
    result.put("length", column.length());
    result.put("precision", column.precision());
    result.put("scale", column.scale());
    result.put("nullable", column.nullable());
    return result;
  }

  private Map<String, Object> actualMap(SchemaColumn column) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("name", safeName(column.name()));
    result.put("type", bounded(column.type(), MAX_FIELD_TYPE_LENGTH));
    result.put("original_type_name", bounded(column.originalTypeName(), MAX_FIELD_TYPE_LENGTH));
    result.put("length", column.length());
    result.put("precision", column.precision());
    result.put("scale", column.scale());
    result.put("nullable", column.nullable());
    return result;
  }

  private SchemaReadResult loadWithTimeout(
      DatabaseMeta connection, String schema, String table, int timeoutSeconds) throws Exception {
    Variables boundedVariables = new Variables();
    if (variables != null) boundedVariables.initializeFrom(variables);
    boundedVariables.setVariable(
        Const.HOP_DATABASE_CONNECTION_TIMEOUT, Integer.toString(timeoutSeconds));
    boundedVariables.setVariable(
        Const.HOP_DATABASE_SOCKET_TIMEOUT, Integer.toString(timeoutSeconds));
    Future<SchemaReadResult> future =
        HopDeepCheckExecutor.submit(
            () -> schemaLoader.load(connection, boundedVariables, schema, table));
    try {
      return future.get(timeoutSeconds, TimeUnit.SECONDS);
    } catch (TimeoutException timeout) {
      future.cancel(true);
      throw timeout;
    } catch (InterruptedException interrupted) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw interrupted;
    } catch (ExecutionException execution) {
      Throwable cause = execution.getCause();
      if (cause instanceof Exception exception) throw exception;
      throw new IllegalStateException("Native schema inspection failed", cause);
    }
  }

  private static SchemaReadResult loadNativeSchema(
      DatabaseMeta connection, IVariables boundedVariables, String schema, String table)
      throws Exception {
    Database database =
        new Database(new LoggingObject("hop-mcp-schema-compare"), boundedVariables, connection);
    try {
      database.connect();
      IRowMeta rowMeta = database.getTableFieldsMeta(schema, table);
      if (rowMeta == null) {
        throw McpException.validation(
            "SCHEMA_UNAVAILABLE", "The native database did not return table metadata.");
      }
      int totalCount = rowMeta.size();
      int returned = Math.min(totalCount, MAX_FIELDS);
      List<SchemaColumn> columns = new ArrayList<>(returned);
      for (int i = 0; i < returned; i++) {
        columns.add(fromValueMeta(rowMeta.getValueMeta(i)));
      }
      return new SchemaReadResult(columns, totalCount, totalCount > MAX_FIELDS);
    } finally {
      database.close();
    }
  }

  private static SchemaColumn fromValueMeta(IValueMeta valueMeta) {
    int nullableCode = valueMeta.getOriginalNullable();
    Boolean nullable =
        nullableCode == ResultSetMetaData.columnNullable
            ? Boolean.TRUE
            : nullableCode == ResultSetMetaData.columnNoNulls ? Boolean.FALSE : null;
    return new SchemaColumn(
        valueMeta.getName(),
        valueMeta.getTypeDesc(),
        valueMeta.getOriginalColumnTypeName(),
        valueMeta.getLength(),
        valueMeta.getPrecision(),
        valueMeta.getOriginalScale(),
        nullable);
  }

  private DatabaseMeta loadNativeConnection(String name) throws Exception {
    if (metadataProvider == null) {
      throw McpException.validation("METADATA_NOT_FOUND", "The metadata provider is unavailable.");
    }
    IHopMetadataSerializer<DatabaseMeta> serializer =
        metadataProvider.getSerializer(DatabaseMeta.class);
    if (serializer == null) {
      throw McpException.validation("METADATA_NOT_FOUND", "RDBMS metadata is unavailable.");
    }
    DatabaseMeta connection = serializer.load(name);
    if (connection == null) {
      throw McpException.validation(
          "METADATA_NOT_FOUND", "The RDBMS metadata object was not found.");
    }
    return connection;
  }

  private static List<ExpectedColumn> parseExpected(List<Map<String, Object>> values) {
    if (values == null) throw new IllegalArgumentException("expected is required");
    if (values.size() > MAX_FIELDS) {
      throw new IllegalArgumentException("expected cannot exceed " + MAX_FIELDS + " fields");
    }
    List<ExpectedColumn> result = new ArrayList<>(values.size());
    Map<String, Boolean> seen = new LinkedHashMap<>();
    for (Map<String, Object> value : values) {
      if (value == null) throw new IllegalArgumentException("expected fields cannot be null");
      String name = text(value.get("name"), "expected.name", MAX_NAME_LENGTH);
      String type = text(value.get("type"), "expected.type", MAX_FIELD_TYPE_LENGTH);
      String key = normalizeName(name);
      if (seen.put(key, Boolean.TRUE) != null) {
        throw new IllegalArgumentException("expected contains duplicate column: " + name);
      }
      result.add(
          new ExpectedColumn(
              name,
              type,
              integer(value.get("length"), "expected.length"),
              integer(value.get("precision"), "expected.precision"),
              integer(value.get("scale"), "expected.scale"),
              bool(value.get("nullable"), "expected.nullable")));
    }
    return result;
  }

  private static Integer integer(Object value, String name) {
    if (value == null) return null;
    if (!(value instanceof Number number)
        || number.longValue() < -1
        || number.longValue() > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(
          name + " must be an integer between -1 and " + Integer.MAX_VALUE);
    }
    return number.intValue();
  }

  private static Boolean bool(Object value, String name) {
    if (value == null) return null;
    if (value instanceof Boolean booleanValue) return booleanValue;
    throw new IllegalArgumentException(name + " must be boolean");
  }

  private static boolean typeMatches(String expected, SchemaColumn actual) {
    String wanted = normalizeType(expected);
    return wanted.equals(normalizeType(actual.type()))
        || (!blank(actual.originalTypeName())
            && wanted.equals(normalizeType(actual.originalTypeName())));
  }

  private void requireDeepCheck() {
    if (!allowDeepCheck) {
      throw new SecurityException(
          "Schema comparison disabled. Restart with --allow-deep-check; it contacts an external system.");
    }
  }

  private static void validateTimeout(int timeoutSeconds) {
    if (timeoutSeconds < 1 || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
      throw new IllegalArgumentException(
          "timeout_seconds must be between 1 and " + MAX_TIMEOUT_SECONDS);
    }
  }

  private static void requireText(String value, String name, int maxLength) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    if (value.length() > maxLength) {
      throw new IllegalArgumentException(name + " exceeds " + maxLength + " characters");
    }
  }

  private static void requireOptionalText(String value, String name, int maxLength) {
    if (value.length() > maxLength) {
      throw new IllegalArgumentException(name + " exceeds " + maxLength + " characters");
    }
  }

  private static String text(Object value, String name, int maxLength) {
    if (value == null || String.valueOf(value).isBlank())
      throw new IllegalArgumentException(name + " is required");
    String result = String.valueOf(value);
    requireText(result, name, maxLength);
    return result;
  }

  private static String normalizeName(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT);
  }

  private static String normalizeType(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
  }

  private static String safeName(String value) {
    return SensitiveData.isSensitiveKey(value)
        ? SensitiveData.redactedMarker()
        : bounded(value, MAX_NAME_LENGTH);
  }

  private static String bounded(String value, int maxLength) {
    if (value == null) return "";
    return value.length() <= maxLength ? value : value.substring(0, maxLength);
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
