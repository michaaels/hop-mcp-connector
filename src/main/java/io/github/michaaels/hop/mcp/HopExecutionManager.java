package io.github.michaaels.hop.mcp;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.metadata.api.IHopMetadataProvider;

/** Tracks a bounded number of explicitly authorized local Hop executions. */
final class HopExecutionManager implements AutoCloseable {
  private static final int MAX_ACTIVE_EXECUTIONS = 4;
  private static final int MAX_RETAINED_EXECUTIONS = 100;
  private final ExecutorService executor =
      Executors.newFixedThreadPool(
          MAX_ACTIVE_EXECUTIONS,
          runnable -> {
            Thread thread = new Thread(runnable, "hop-mcp-connector-async-execution");
            thread.setDaemon(true);
            return thread;
          });
  private final Map<String, Job> jobs = new ConcurrentHashMap<>();
  private final AtomicInteger active = new AtomicInteger();

  Map<String, Object> start(
      Path file,
      IVariables variables,
      IHopMetadataProvider metadataProvider,
      String runConfiguration,
      Map<String, String> parameters,
      int timeoutSeconds) {
    if (timeoutSeconds < 1 || timeoutSeconds > HopNative.MAX_EXECUTION_TIMEOUT_SECONDS) {
      throw new IllegalArgumentException(
          "timeout_seconds must be between 1 and " + HopNative.MAX_EXECUTION_TIMEOUT_SECONDS);
    }
    retainCompletedJobs();
    if (active.incrementAndGet() > MAX_ACTIVE_EXECUTIONS) {
      active.decrementAndGet();
      throw new IllegalStateException(
          "Maximum concurrent MCP executions reached: " + MAX_ACTIVE_EXECUTIONS);
    }
    String id = UUID.randomUUID().toString();
    Job job = new Job(file, runConfiguration, System.currentTimeMillis());
    jobs.put(id, job);
    try {
      job.task =
          executor.submit(
              () -> {
                job.taskStarted = true;
                try {
                  job.result =
                      HopNative.execute(
                          file,
                          variables,
                          metadataProvider,
                          runConfiguration,
                          parameters,
                          timeoutSeconds,
                          job.logChannelId);
                  job.state =
                      Boolean.TRUE.equals(job.result.get("timed_out")) ? "timed_out" : "completed";
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  job.state = job.stopRequested ? "stopped" : "failed";
                  job.error =
                      Map.of(
                          "error",
                          e.getClass().getSimpleName(),
                          "message",
                          "Execution interrupted");
                } catch (Exception e) {
                  job.state = job.stopRequested ? "stopped" : "failed";
                  job.error =
                      Map.of(
                          "error", e.getClass().getSimpleName(),
                          "message", HopXml.redact(String.valueOf(e.getMessage())));
                } finally {
                  job.finishedAt = System.currentTimeMillis();
                  releaseActive(job);
                }
              });
    } catch (RuntimeException e) {
      jobs.remove(id);
      active.decrementAndGet();
      throw e;
    }
    return status(id);
  }

  Map<String, Object> status(String id) {
    Job job = job(id);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("operation_id", id);
    result.put("path", job.file.toString());
    result.put("run_configuration", job.runConfiguration);
    result.put("state", job.state);
    result.put("started_at", job.startedAt);
    result.put("active_executions", active.get());
    if (job.finishedAt > 0) result.put("finished_at", job.finishedAt);
    if (job.logChannelId.get() != null) {
      result.put("log_channel_id", job.logChannelId.get());
    }
    if (job.result != null) result.put("result", job.result);
    if (job.error != null) result.put("error", job.error);
    if (job.stopRequested) result.put("stop_requested", true);
    return result;
  }

  Map<String, Object> stop(String id) {
    Job job = job(id);
    if ("running".equals(job.state)) {
      job.stopRequested = true;
      job.state = "stopping";
      Future<?> task = job.task;
      if (task != null && task.cancel(true) && !job.taskStarted) {
        job.state = "stopped";
        job.finishedAt = System.currentTimeMillis();
        job.error = Map.of("error", "CancellationException", "message", "Execution cancelled");
        releaseActive(job);
      }
    }
    return status(id);
  }

  private void releaseActive(Job job) {
    if (job.activeReleased.compareAndSet(false, true)) active.decrementAndGet();
  }

  private Job job(String id) {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("operation_id is required");
    }
    Job job = jobs.get(id);
    if (job == null) throw new IllegalArgumentException("Unknown execution operation: " + id);
    return job;
  }

  private void retainCompletedJobs() {
    if (jobs.size() < MAX_RETAINED_EXECUTIONS) return;
    for (String id : jobs.keySet()) {
      Job job = jobs.get(id);
      if (job != null && !"running".equals(job.state) && !"stopping".equals(job.state)) {
        jobs.remove(id);
        if (jobs.size() < MAX_RETAINED_EXECUTIONS) break;
      }
    }
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }

  private static final class Job {
    private final Path file;
    private final String runConfiguration;
    private final long startedAt;
    private final AtomicReference<String> logChannelId = new AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicBoolean activeReleased =
        new java.util.concurrent.atomic.AtomicBoolean();
    private volatile long finishedAt;
    private volatile String state = "running";
    private volatile boolean stopRequested;
    private volatile boolean taskStarted;
    private volatile Future<?> task;
    private volatile Map<String, Object> result;
    private volatile Map<String, Object> error;

    private Job(Path file, String runConfiguration, long startedAt) {
      this.file = file;
      this.runConfiguration = runConfiguration;
      this.startedAt = startedAt;
    }
  }
}
