package io.github.michaaels.hop.mcp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hop.core.ICheckResult;
import org.apache.hop.core.Result;
import org.apache.hop.core.logging.LoggingObject;
import org.apache.hop.core.plugins.IPlugin;
import org.apache.hop.core.plugins.IPluginType;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.config.PipelineRunConfiguration;
import org.apache.hop.pipeline.engine.IPipelineEngine;
import org.apache.hop.pipeline.engine.PipelineEngineFactory;
import org.apache.hop.workflow.WorkflowMeta;
import org.apache.hop.workflow.config.WorkflowRunConfiguration;
import org.apache.hop.workflow.engine.IWorkflowEngine;
import org.apache.hop.workflow.engine.WorkflowEngineFactory;

final class HopNative {
  static final int MAX_EXECUTION_TIMEOUT_SECONDS = 900;

  private HopNative() {}

  static Map<String, Object> plugins() {
    Map<String, Object> out = new LinkedHashMap<>();
    List<Map<String, Object>> types = new ArrayList<>();
    PluginRegistry registry = PluginRegistry.getInstance();
    int total = 0;
    for (Class<? extends IPluginType> type : registry.getPluginTypes()) {
      List<IPlugin> plugins = registry.getPlugins(type);
      total += plugins.size();
      List<Map<String, Object>> sample = new ArrayList<>();
      for (IPlugin plugin : plugins) {
        if (sample.size() >= 50) break;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ids", List.of(plugin.getIds()));
        row.put("name", String.valueOf(plugin.getName()));
        row.put("description", String.valueOf(plugin.getDescription()));
        row.put("category", String.valueOf(plugin.getCategory()));
        sample.add(row);
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("type", type.getName());
      row.put("count", plugins.size());
      row.put("plugins", sample);
      types.add(row);
    }
    out.put("plugin_registry", registry.getClass().getName());
    out.put("plugin_type_count", types.size());
    out.put("plugin_count", total);
    out.put("plugin_types", types);
    return out;
  }

  static Map<String, Object> plugins(String typeFilter, String query, int offset, int limit) {
    if (offset < 0) throw new IllegalArgumentException("offset must be zero or greater");
    if (limit < 1 || limit > 50)
      throw new IllegalArgumentException("limit must be between 1 and 50");
    String normalizedType =
        typeFilter == null || typeFilter.isBlank() ? null : typeFilter.toLowerCase(Locale.ROOT);
    String normalizedQuery =
        query == null || query.isBlank() ? null : query.toLowerCase(Locale.ROOT);
    PluginRegistry registry = PluginRegistry.getInstance();
    List<Map<String, Object>> types = new ArrayList<>();
    List<Map<String, Object>> results = new ArrayList<>();
    int total = 0, matched = 0;
    for (Class<? extends IPluginType> type : registry.getPluginTypes()) {
      List<IPlugin> registered = registry.getPlugins(type);
      total += registered.size();
      types.add(Map.of("type", type.getName(), "count", registered.size()));
      if (!matchesType(type, normalizedType)) continue;
      for (IPlugin plugin : registered) {
        if (!matchesQuery(plugin, normalizedQuery)) continue;
        if (matched >= offset && results.size() < limit)
          results.add(pluginRow(type.getName(), plugin));
        matched++;
      }
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("plugin_registry", registry.getClass().getName());
    out.put("plugin_type_count", types.size());
    out.put("plugin_count", total);
    out.put("matched_plugin_count", matched);
    out.put("returned_plugin_count", results.size());
    out.put("offset", offset);
    out.put("limit", limit);
    out.put("has_more", offset + results.size() < matched);
    if (normalizedType != null) out.put("type_filter", typeFilter);
    if (normalizedQuery != null) out.put("query", query);
    out.put("plugin_types", types);
    out.put("plugins", results);
    return out;
  }

  private static boolean matchesType(Class<? extends IPluginType> type, String filter) {
    return filter == null
        || type.getName().toLowerCase(Locale.ROOT).equals(filter)
        || type.getSimpleName().toLowerCase(Locale.ROOT).equals(filter);
  }

  private static boolean matchesQuery(IPlugin plugin, String query) {
    if (query == null) return true;
    if (contains(plugin.getName(), query)
        || contains(plugin.getDescription(), query)
        || contains(plugin.getCategory(), query)) return true;
    if (plugin.getIds() != null)
      for (String id : plugin.getIds()) if (contains(id, query)) return true;
    return false;
  }

  private static boolean contains(Object value, String query) {
    return value != null && String.valueOf(value).toLowerCase(Locale.ROOT).contains(query);
  }

  private static Map<String, Object> pluginRow(String type, IPlugin plugin) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("type", type);
    row.put("ids", List.of(plugin.getIds()));
    row.put("name", String.valueOf(plugin.getName()));
    row.put("description", String.valueOf(plugin.getDescription()));
    row.put("category", String.valueOf(plugin.getCategory()));
    return row;
  }

  static Map<String, Object> deepCheck(
      Path file, IVariables variables, IHopMetadataProvider metadataProvider) throws Exception {
    String name = file.getFileName().toString().toLowerCase();
    List<ICheckResult> remarks = new ArrayList<>();
    if (name.endsWith(".hpl")) {
      PipelineMeta meta = new PipelineMeta(file.toString(), metadataProvider, variables);
      meta.checkTransforms(remarks, false, null, variables, metadataProvider);
    } else if (name.endsWith(".hwf")) {
      WorkflowMeta meta = new WorkflowMeta(variables, file.toString(), metadataProvider);
      meta.checkActions(remarks, false, null, variables, metadataProvider);
    } else throw new IllegalArgumentException("Deep check supports .hpl and .hwf only");
    int errors = 0, warnings = 0, comments = 0, ok = 0, none = 0;
    List<Map<String, Object>> issues = new ArrayList<>();
    for (ICheckResult r : remarks) {
      int type = r.getType();
      if (type == ICheckResult.TYPE_RESULT_ERROR) errors++;
      else if (type == ICheckResult.TYPE_RESULT_WARNING) warnings++;
      else if (type == ICheckResult.TYPE_RESULT_COMMENT) comments++;
      else if (type == ICheckResult.TYPE_RESULT_OK) ok++;
      else none++;
      if (type != ICheckResult.TYPE_RESULT_OK && issues.size() < 500)
        issues.add(Map.of("type", type, "text", HopXml.redact(String.valueOf(r.getText()))));
    }
    return Map.of(
        "checker",
        "apache-hop",
        "deep",
        true,
        "may_access_external_systems",
        true,
        "valid",
        errors == 0,
        "summary",
        Map.of(
            "errors", errors, "warnings", warnings, "comments", comments, "ok", ok, "none", none),
        "issues",
        issues);
  }

  static Map<String, Object> execute(
      Path file,
      IVariables variables,
      IHopMetadataProvider metadataProvider,
      String runConfigurationName,
      Map<String, String> parameters,
      int timeoutSeconds)
      throws Exception {
    return execute(
        file, variables, metadataProvider, runConfigurationName, parameters, timeoutSeconds, null);
  }

  static Map<String, Object> execute(
      Path file,
      IVariables variables,
      IHopMetadataProvider metadataProvider,
      String runConfigurationName,
      Map<String, String> parameters,
      int timeoutSeconds,
      AtomicReference<String> logChannelId)
      throws Exception {
    if (variables == null || metadataProvider == null) {
      throw new IllegalStateException(
          "Hop execution requires an initialized variables space and metadata provider");
    }
    if (timeoutSeconds < 1 || timeoutSeconds > MAX_EXECUTION_TIMEOUT_SECONDS) {
      throw new IllegalArgumentException(
          "timeout_seconds must be between 1 and " + MAX_EXECUTION_TIMEOUT_SECONDS);
    }
    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
    String configuration =
        runConfigurationName == null || runConfigurationName.isBlank()
            ? "local"
            : variables.resolve(runConfigurationName);
    if (name.endsWith(".hpl")) {
      ensureLocalPipelineConfiguration(configuration, metadataProvider);
      return executePipeline(
          file,
          variables,
          metadataProvider,
          configuration,
          parameters,
          timeoutSeconds,
          logChannelId);
    }
    if (name.endsWith(".hwf")) {
      ensureLocalWorkflowConfiguration(configuration, metadataProvider);
      return executeWorkflow(
          file,
          variables,
          metadataProvider,
          configuration,
          parameters,
          timeoutSeconds,
          logChannelId);
    }
    throw new IllegalArgumentException("Execution supports .hpl and .hwf only");
  }

  private static Map<String, Object> executePipeline(
      Path file,
      IVariables variables,
      IHopMetadataProvider metadataProvider,
      String configuration,
      Map<String, String> parameters,
      int timeoutSeconds,
      AtomicReference<String> logChannelId)
      throws Exception {
    AtomicReference<IPipelineEngine<PipelineMeta>> engineRef = new AtomicReference<>();
    ExecutorService executor = executionExecutor();
    long started = System.currentTimeMillis();
    Future<Map<String, Object>> future =
        executor.submit(
            () -> {
              IPipelineEngine<PipelineMeta> engine = null;
              try {
                PipelineMeta meta = new PipelineMeta(file.toString(), metadataProvider, variables);
                engine =
                    PipelineEngineFactory.createPipelineEngine(
                        variables, configuration, metadataProvider, meta);
                engineRef.set(engine);
                if (logChannelId != null) logChannelId.set(engine.getLogChannelId());
                applyParameters(engine, parameters);
                engine.activateParameters(engine);
                engine.prepareExecution();
                engine.startThreads();
                engine.waitUntilFinished();
                return pipelineResult(file, configuration, engine, started, false);
              } finally {
                if (engine != null) engine.cleanup();
              }
            });
    try {
      return future.get(timeoutSeconds, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      IPipelineEngine<PipelineMeta> engine = engineRef.get();
      if (engine != null) engine.stopAll();
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw e;
    } catch (TimeoutException e) {
      IPipelineEngine<PipelineMeta> engine = engineRef.get();
      if (engine != null) engine.stopAll();
      future.cancel(true);
      return timeoutResult(
          file, configuration, engine == null ? null : engine.getLogChannelId(), started);
    } finally {
      executor.shutdownNow();
    }
  }

  private static Map<String, Object> executeWorkflow(
      Path file,
      IVariables variables,
      IHopMetadataProvider metadataProvider,
      String configuration,
      Map<String, String> parameters,
      int timeoutSeconds,
      AtomicReference<String> logChannelId)
      throws Exception {
    AtomicReference<IWorkflowEngine<WorkflowMeta>> engineRef = new AtomicReference<>();
    ExecutorService executor = executionExecutor();
    long started = System.currentTimeMillis();
    Future<Map<String, Object>> future =
        executor.submit(
            () -> {
              IWorkflowEngine<WorkflowMeta> engine = null;
              WorkflowMeta meta = new WorkflowMeta(variables, file.toString(), metadataProvider);
              engine =
                  WorkflowEngineFactory.createWorkflowEngine(
                      variables,
                      configuration,
                      metadataProvider,
                      meta,
                      new LoggingObject("ApacheHopMCP"));
              engineRef.set(engine);
              if (logChannelId != null) {
                logChannelId.set(engine.getLogChannel().getLogChannelId());
              }
              applyParameters(engine, parameters);
              engine.activateParameters(engine);
              Result result = engine.startExecution();
              return workflowResult(file, configuration, engine, result, started, false);
            });
    try {
      return future.get(timeoutSeconds, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      IWorkflowEngine<WorkflowMeta> engine = engineRef.get();
      if (engine != null) engine.stopExecution();
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw e;
    } catch (TimeoutException e) {
      IWorkflowEngine<WorkflowMeta> engine = engineRef.get();
      if (engine != null) engine.stopExecution();
      future.cancel(true);
      return timeoutResult(
          file,
          configuration,
          engine == null ? null : engine.getLogChannel().getLogChannelId(),
          started);
    } finally {
      executor.shutdownNow();
    }
  }

  private static ExecutorService executionExecutor() {
    return Executors.newSingleThreadExecutor(
        runnable -> {
          Thread thread = new Thread(runnable, "hop-mcp-connector-execution");
          thread.setDaemon(true);
          return thread;
        });
  }

  private static void ensureLocalPipelineConfiguration(
      String name, IHopMetadataProvider metadataProvider) throws Exception {
    PipelineRunConfiguration configuration =
        metadataProvider.getSerializer(PipelineRunConfiguration.class).load(name);
    if (configuration == null || configuration.getEngineRunConfiguration() == null) {
      throw new IllegalArgumentException(
          "Pipeline run configuration not found or has no engine: " + name);
    }
    ensureLocal(configuration.getEngineRunConfiguration().getEnginePluginId(), name);
  }

  private static void ensureLocalWorkflowConfiguration(
      String name, IHopMetadataProvider metadataProvider) throws Exception {
    WorkflowRunConfiguration configuration =
        metadataProvider.getSerializer(WorkflowRunConfiguration.class).load(name);
    if (configuration == null || configuration.getEngineRunConfiguration() == null) {
      throw new IllegalArgumentException(
          "Workflow run configuration not found or has no engine: " + name);
    }
    ensureLocal(configuration.getEngineRunConfiguration().getEnginePluginId(), name);
  }

  private static void ensureLocal(String engineId, String configuration) {
    if (!"local".equalsIgnoreCase(engineId) && !"localsingle".equalsIgnoreCase(engineId)) {
      throw new SecurityException(
          "hop_execute only permits a local engine; run configuration '"
              + configuration
              + "' uses '"
              + engineId
              + "'");
    }
  }

  private static void applyParameters(
      org.apache.hop.core.parameters.INamedParameters engine, Map<String, String> parameters)
      throws Exception {
    if (parameters == null) return;
    for (Map.Entry<String, String> entry : parameters.entrySet()) {
      if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "parameters must contain non-empty names and non-null values");
      }
      engine.setParameterValue(entry.getKey(), entry.getValue());
    }
  }

  private static Map<String, Object> pipelineResult(
      Path file,
      String configuration,
      IPipelineEngine<PipelineMeta> engine,
      long started,
      boolean timedOut) {
    Result result = engine.getResult();
    long errors = result == null ? engine.getErrors() : result.getNrErrors();
    return executionResult(
        "pipeline",
        file,
        configuration,
        engine.getLogChannelId(),
        errors == 0,
        errors,
        engine.getStatusDescription(),
        started,
        timedOut);
  }

  private static Map<String, Object> workflowResult(
      Path file,
      String configuration,
      IWorkflowEngine<WorkflowMeta> engine,
      Result result,
      long started,
      boolean timedOut) {
    long errors = result == null ? 0 : result.getNrErrors();
    return executionResult(
        "workflow",
        file,
        configuration,
        engine.getLogChannel().getLogChannelId(),
        result != null && result.isResult() && errors == 0,
        errors,
        engine.getStatusDescription(),
        started,
        timedOut);
  }

  private static Map<String, Object> timeoutResult(
      Path file, String configuration, String channel, long started) {
    return executionResult(
        file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".hpl")
            ? "pipeline"
            : "workflow",
        file,
        configuration,
        channel,
        false,
        -1,
        "timeout",
        started,
        true);
  }

  private static Map<String, Object> executionResult(
      String kind,
      Path file,
      String configuration,
      String channel,
      boolean ok,
      long errors,
      String status,
      long started,
      boolean timedOut) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("kind", kind);
    result.put("path", file.toString());
    result.put("run_configuration", configuration);
    result.put("ok", ok);
    result.put("timed_out", timedOut);
    result.put("error_count", errors);
    result.put("status", status == null ? "" : status);
    result.put("log_channel_id", channel == null ? "" : channel);
    result.put("started_at", started);
    result.put("finished_at", System.currentTimeMillis());
    result.put("diagnostics", "Use hop_logs with log_channel_id for bounded execution details.");
    return result;
  }
}
