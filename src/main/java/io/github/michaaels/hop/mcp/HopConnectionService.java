package io.github.michaaels.hop.mcp;

import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.apache.hop.core.Const;
import org.apache.hop.core.database.DatabaseMeta;
import org.apache.hop.core.database.DatabaseTestResults;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.metadata.api.IHopMetadataSerializer;

/** Explicitly authorized, bounded native connection checks. */
final class HopConnectionService {
  static final int DEFAULT_TIMEOUT_SECONDS = 30;
  static final int MAX_TIMEOUT_SECONDS = 60;
  static final int MAX_NAME_LENGTH = 512;

  private static final Pattern URI_CREDENTIALS =
      Pattern.compile("(?i)(://[^\\s/@:]+:)[^\\s/@]+(@)");
  private static final Pattern URI_SECRET_QUERY =
      Pattern.compile(
          "(?i)([?&](?:password|passwd|pwd|token|secret|api[_-]?key)=)[^&\\s]+",
          Pattern.CASE_INSENSITIVE);

  @FunctionalInterface
  interface ConnectionProbe {
    DatabaseTestResults test(DatabaseMeta connection, IVariables variables) throws Exception;
  }

  @FunctionalInterface
  interface ConnectionLoader {
    DatabaseMeta load(String name) throws Exception;
  }

  private final IHopMetadataProvider metadataProvider;
  private final IVariables variables;
  private final boolean allowDeepCheck;
  private final ConnectionProbe probe;
  private final ConnectionLoader loader;

  HopConnectionService(
      IHopMetadataProvider metadataProvider, IVariables variables, boolean allowDeepCheck) {
    this(
        metadataProvider,
        variables,
        allowDeepCheck,
        (connection, boundedVariables) -> connection.testConnectionSuccess(boundedVariables),
        null);
  }

  HopConnectionService(
      IHopMetadataProvider metadataProvider,
      IVariables variables,
      boolean allowDeepCheck,
      ConnectionProbe probe) {
    this(metadataProvider, variables, allowDeepCheck, probe, null);
  }

  HopConnectionService(
      IHopMetadataProvider metadataProvider,
      IVariables variables,
      boolean allowDeepCheck,
      ConnectionProbe probe,
      ConnectionLoader loader) {
    this.metadataProvider = metadataProvider;
    this.variables = variables;
    this.allowDeepCheck = allowDeepCheck;
    this.probe = probe;
    this.loader = loader == null ? this::loadNative : loader;
  }

  Map<String, Object> testConnection(String name, int timeoutSeconds) throws Exception {
    requireDeepCheck();
    requireName(name);
    validateTimeout(timeoutSeconds);
    DatabaseMeta connection = loader.load(name);

    Variables boundedVariables = new Variables();
    if (variables != null) boundedVariables.initializeFrom(variables);
    boundedVariables.setVariable(
        Const.HOP_DATABASE_CONNECTION_TIMEOUT, Integer.toString(timeoutSeconds));
    boundedVariables.setVariable(
        Const.HOP_DATABASE_SOCKET_TIMEOUT, Integer.toString(timeoutSeconds));

    Future<DatabaseTestResults> future =
        HopDeepCheckExecutor.submit(
            () -> {
              int previousLoginTimeout = DriverManager.getLoginTimeout();
              try {
                return probe.test(connection, boundedVariables);
              } finally {
                DriverManager.setLoginTimeout(previousLoginTimeout);
              }
            });
    try {
      DatabaseTestResults result = future.get(timeoutSeconds, TimeUnit.SECONDS);
      boolean success = result != null && result.isSuccess();
      String message = result == null ? "No diagnostic message was returned." : result.getMessage();
      return result(name, success ? "success" : "failure", success, timeoutSeconds, message);
    } catch (TimeoutException timeout) {
      future.cancel(true);
      return result(
          name,
          "timeout",
          false,
          timeoutSeconds,
          "The native connection test exceeded the configured timeout.");
    } catch (InterruptedException interrupted) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw interrupted;
    } catch (ExecutionException execution) {
      Throwable cause = execution.getCause();
      return result(
          name,
          "failure",
          false,
          timeoutSeconds,
          cause == null ? "The native connection test failed." : cause.getMessage());
    }
  }

  private DatabaseMeta loadNative(String name) throws Exception {
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

  private Map<String, Object> result(
      String name, String status, boolean success, int timeoutSeconds, String message) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("type", "rdbms");
    result.put("name", name);
    result.put("status", status);
    result.put("success", success);
    result.put("timeout_seconds", timeoutSeconds);
    result.put("message", sanitizeReport(message));
    result.put("redaction_applied", true);
    return result;
  }

  private static String sanitizeReport(String message) {
    String safe = SensitiveData.redactText(message == null ? "" : message);
    if (safe == null || safe.isBlank()) safe = "No diagnostic message was returned.";
    safe = URI_CREDENTIALS.matcher(safe).replaceAll("$1" + SensitiveData.REDACTED + "$2");
    return URI_SECRET_QUERY.matcher(safe).replaceAll("$1" + SensitiveData.REDACTED);
  }

  private void requireDeepCheck() {
    if (!allowDeepCheck) {
      throw new SecurityException(
          "Connection testing disabled. Restart with --allow-deep-check; it contacts external systems.");
    }
  }

  private static void requireName(String name) {
    if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
    if (name.length() > MAX_NAME_LENGTH)
      throw new IllegalArgumentException("name exceeds " + MAX_NAME_LENGTH + " characters");
  }

  private static void validateTimeout(int timeoutSeconds) {
    if (timeoutSeconds < 1 || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
      throw new IllegalArgumentException(
          "timeout_seconds must be between 1 and " + MAX_TIMEOUT_SECONDS);
    }
  }
}
