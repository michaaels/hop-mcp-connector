package io.github.michaaels.hop.mcp;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Shared bounded worker for explicitly authorized external deep checks. */
final class HopDeepCheckExecutor {
  static final int MAX_QUEUED_CHECKS = 8;

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
    try {
      return EXECUTOR.submit(task);
    } catch (RejectedExecutionException busy) {
      throw McpException.precondition(
          "DEEP_CHECK_BUSY",
          "The bounded deep-check worker is busy. Retry after an active external check completes.",
          true);
    }
  }
}
