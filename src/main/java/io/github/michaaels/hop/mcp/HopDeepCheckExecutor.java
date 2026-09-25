package io.github.michaaels.hop.mcp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Shared bounded worker for explicitly authorized external deep checks. */
final class HopDeepCheckExecutor {
  static final int MAX_QUEUED_CHECKS = 8;

  private static final AtomicLong ACTIVE = new AtomicLong();
  private static final AtomicLong COMPLETED = new AtomicLong();
  private static final AtomicLong TIMEOUTS = new AtomicLong();
  private static final AtomicLong CANCELLED = new AtomicLong();
  private static final AtomicLong REJECTED = new AtomicLong();
  private static final AtomicLong SUBMITTED = new AtomicLong();
  private static final AtomicReference<TrackedTask<?>> DEGRADED_TASK = new AtomicReference<>();

  private static final ThreadPoolExecutor EXECUTOR =
      new ThreadPoolExecutor(
          1,
          1,
          0L,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(MAX_QUEUED_CHECKS),
          runnable -> {
            Thread thread = new Thread(runnable, "hop-mcp-deep-check");
            thread.setDaemon(true);
            return thread;
          },
          new ThreadPoolExecutor.AbortPolicy());

  private HopDeepCheckExecutor() {}

  static <T> Future<T> submit(Callable<T> task) throws McpException {
    Objects.requireNonNull(task, "task");
    TrackedTask<T> tracked = new TrackedTask<>(task);
    try {
      EXECUTOR.execute(tracked);
      SUBMITTED.incrementAndGet();
      return tracked;
    } catch (RejectedExecutionException busy) {
      REJECTED.incrementAndGet();
      throw McpException.precondition(
          "DEEP_CHECK_BUSY",
          "The bounded deep-check worker is busy. Retry after an active external check completes.",
          true);
    }
  }

  static void cancel(Future<?> future, boolean timedOut) {
    if (!(future instanceof TrackedTask<?> tracked)) {
      if (timedOut) TIMEOUTS.incrementAndGet();
      future.cancel(true);
      return;
    }

    if (timedOut && tracked.timeoutCounted.compareAndSet(false, true)) TIMEOUTS.incrementAndGet();
    if (!tracked.cancel(true)) return;
    CANCELLED.incrementAndGet();
    if (EXECUTOR.remove(tracked)) return;

    if (tracked.state.running.get() && !tracked.state.finished.get()) {
      DEGRADED_TASK.set(tracked);
      if (tracked.state.finished.get()) DEGRADED_TASK.compareAndSet(tracked, null);
    }
  }

  static Map<String, Object> metrics() {
    Map<String, Object> result = new LinkedHashMap<>();
    TrackedTask<?> degraded = DEGRADED_TASK.get();
    if (degraded != null && degraded.state.finished.get()) {
      DEGRADED_TASK.compareAndSet(degraded, null);
      degraded = DEGRADED_TASK.get();
    }
    result.put("active", ACTIVE.get());
    result.put("queued", EXECUTOR.getQueue().size());
    result.put("completed", COMPLETED.get());
    result.put("timeouts", TIMEOUTS.get());
    result.put("cancelled", CANCELLED.get());
    result.put("rejected", REJECTED.get());
    result.put("submitted", SUBMITTED.get());
    result.put("health", degraded == null ? "HEALTHY" : "DEGRADED");
    return Map.copyOf(result);
  }

  private static <T> T invoke(TaskState state, Callable<T> task) throws Exception {
    state.running.set(true);
    TrackedTask<?> runningTask = state.owner.get();
    if (runningTask != null && runningTask.isCancelled()) DEGRADED_TASK.set(runningTask);
    ACTIVE.incrementAndGet();
    try {
      return task.call();
    } finally {
      state.finished.set(true);
      state.running.set(false);
      ACTIVE.decrementAndGet();
      COMPLETED.incrementAndGet();
      TrackedTask<?> owner = state.owner.get();
      if (owner != null) DEGRADED_TASK.compareAndSet(owner, null);
    }
  }

  private static final class TrackedTask<T> extends FutureTask<T> {
    private final TaskState state;
    private final AtomicBoolean timeoutCounted = new AtomicBoolean();

    private TrackedTask(Callable<T> task) {
      this(new TaskState(), task);
    }

    private TrackedTask(TaskState state, Callable<T> task) {
      super(() -> invoke(state, task));
      this.state = state;
      state.owner.set(this);
    }
  }

  private static final class TaskState {
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicReference<TrackedTask<?>> owner = new AtomicReference<>();
  }
}
