package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class HopExecutionManagerTest {
  @Test
  void stoppedCallersRetainTheirCapacityUntilUninterruptibleNativeWorkersFinish() throws Exception {
    CountDownLatch entered = new CountDownLatch(4);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger lateStarts = new AtomicInteger();
    ExecutorService callers = Executors.newFixedThreadPool(4);
    try (HopExecutionManager manager =
        new HopExecutionManager(
            (file, variables, provider, configuration, parameters, seconds, channel, lifecycle) ->
                blockedNativeCall(lifecycle, entered, release, lateStarts, false),
            callers)) {
      List<String> ids = new ArrayList<>();
      try {
        for (int i = 0; i < 4; i++) ids.add(start(manager));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        for (String id : ids) manager.stop(id);
        for (String id : ids) {
          Map<String, Object> status = awaitState(manager, id, "stopping");
          assertEquals(true, status.get("native_worker_active"));
          assertEquals(4, status.get("active_executions"));
          assertFalse(status.containsKey("finished_at"));
          assertSchemaConforms(status);
        }
        assertThrows(IllegalStateException.class, () -> start(manager));
      } finally {
        release.countDown();
      }
      for (String id : ids) awaitState(manager, id, "stopped");
      assertEquals(0, manager.status(ids.getFirst()).get("active_executions"));
      assertEquals(
          0, lateStarts.get(), "Cancelled workers must not continue to the next native phase");
      String next = start(manager);
      Map<String, Object> completed = awaitState(manager, next, "completed");
      assertEquals(false, completed.get("native_worker_active"));
      assertEquals(0, completed.get("active_executions"));
      assertEquals(1, lateStarts.get());
    } finally {
      release.countDown();
      callers.shutdownNow();
      assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void timeoutRemainsStoppingWithItsResultUntilTheNativeWorkerExits() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService callers = Executors.newSingleThreadExecutor();
    try (HopExecutionManager manager =
        new HopExecutionManager(
            (file, variables, provider, configuration, parameters, seconds, channel, lifecycle) ->
                blockedNativeCall(lifecycle, entered, release, new AtomicInteger(), true),
            callers)) {
      String id = start(manager);
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      Map<String, Object> pending = awaitState(manager, id, "stopping");
      assertEquals(true, ((Map<?, ?>) pending.get("result")).get("timed_out"));
      assertEquals(true, pending.get("native_worker_active"));
      assertEquals(1, pending.get("active_executions"));
      assertFalse(pending.containsKey("finished_at"));
      release.countDown();
      Map<String, Object> timedOut = awaitState(manager, id, "timed_out");
      assertEquals(false, timedOut.get("native_worker_active"));
      assertEquals(0, timedOut.get("active_executions"));
      assertTrue(timedOut.containsKey("finished_at"));
    } finally {
      release.countDown();
      callers.shutdownNow();
      assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void aCancelledQueuedCallerNeverInvokesHopAndReleasesCapacityOnce() throws Exception {
    ExecutorService callers = Executors.newSingleThreadExecutor();
    CountDownLatch occupied = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Future<?> blocker =
        callers.submit(
            () -> {
              occupied.countDown();
              awaitUninterruptibly(release);
            });
    AtomicInteger calls = new AtomicInteger();
    try (HopExecutionManager manager =
        new HopExecutionManager(
            (file, variables, provider, configuration, parameters, seconds, channel, lifecycle) -> {
              calls.incrementAndGet();
              return Map.of();
            },
            callers)) {
      assertTrue(occupied.await(5, TimeUnit.SECONDS));
      String id = start(manager);
      Map<String, Object> stopped = manager.stop(id);
      assertEquals("stopped", stopped.get("state"));
      assertEquals(false, stopped.get("native_worker_active"));
      assertEquals(0, stopped.get("active_executions"));
      manager.stop(id);
      release.countDown();
      blocker.get(5, TimeUnit.SECONDS);
      callers.submit(() -> {}).get(5, TimeUnit.SECONDS);
      assertEquals(0, calls.get());
      assertEquals(0, manager.status(id).get("active_executions"));
    } finally {
      release.countDown();
      callers.shutdownNow();
      assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void aCancelledQueuedNativeWorkerDoesNotStartOrHoldCapacity() throws Exception {
    ExecutorService nativePool = Executors.newSingleThreadExecutor();
    CountDownLatch occupied = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger completed = new AtomicInteger();
    AtomicInteger calls = new AtomicInteger();
    HopExecutionLifecycle lifecycle = new HopExecutionLifecycle(completed::incrementAndGet);
    try {
      nativePool.submit(
          () -> {
            occupied.countDown();
            awaitUninterruptibly(release);
          });
      assertTrue(occupied.await(5, TimeUnit.SECONDS));
      Future<Integer> task =
          HopNative.submitExecution(nativePool, lifecycle, calls::incrementAndGet);
      lifecycle.cancel();
      assertTrue(task.cancel(true));
      lifecycle.callerFinished();
      assertFalse(lifecycle.nativeWorkerActive());
      assertEquals(1, completed.get());
      release.countDown();
      nativePool.submit(() -> {}).get(5, TimeUnit.SECONDS);
      assertEquals(0, calls.get());
      assertEquals(1, completed.get());
    } finally {
      release.countDown();
      nativePool.shutdownNow();
      assertTrue(nativePool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void nativeSubmissionFailureDoesNotLeakAnActiveWorker() {
    ExecutorService nativePool = Executors.newSingleThreadExecutor();
    nativePool.shutdown();
    AtomicInteger completed = new AtomicInteger();
    HopExecutionLifecycle lifecycle = new HopExecutionLifecycle(completed::incrementAndGet);
    assertThrows(
        RejectedExecutionException.class,
        () -> HopNative.submitExecution(nativePool, lifecycle, () -> Map.of()));
    lifecycle.callerFinished();
    assertFalse(lifecycle.nativeWorkerActive());
    assertEquals(1, completed.get());
  }

  private static Map<String, Object> blockedNativeCall(
      HopExecutionLifecycle lifecycle,
      CountDownLatch entered,
      CountDownLatch release,
      AtomicInteger lateStarts,
      boolean timeout)
      throws Exception {
    ExecutorService nativePool = Executors.newSingleThreadExecutor();
    Future<Map<String, Object>> task =
        HopNative.submitExecution(
            nativePool,
            lifecycle,
            () -> {
              entered.countDown();
              // Some JDBC metadata/plugin calls ignore interruption. Returning from Future.get or
              // observing Future.isDone must not be treated as evidence that this work has
              // finished.
              awaitUninterruptibly(release);
              lifecycle.checkCancellation();
              lateStarts.incrementAndGet();
              return Map.of("ok", true);
            });
    try {
      return task.get(timeout ? 1 : 10, TimeUnit.SECONDS);
    } catch (TimeoutException expected) {
      lifecycle.cancel();
      return Map.of("timed_out", true);
    } finally {
      task.cancel(true);
      nativePool.shutdownNow();
    }
  }

  private static void awaitUninterruptibly(CountDownLatch release) {
    boolean interrupted = false;
    while (true) {
      try {
        release.await();
        break;
      } catch (InterruptedException expected) {
        interrupted = true;
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }

  private static String start(HopExecutionManager manager) {
    return (String)
        manager
            .start(Path.of("fixture.hpl"), null, null, "local", Map.of(), 10)
            .get("operation_id");
  }

  private static Map<String, Object> awaitState(
      HopExecutionManager manager, String id, String state) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    Map<String, Object> result;
    do {
      result = manager.status(id);
      if (state.equals(result.get("state"))) return result;
      Thread.sleep(10);
    } while (System.nanoTime() < deadline);
    fail("Expected " + state + ": " + result);
    return result;
  }

  private static void assertSchemaConforms(Map<String, Object> result) {
    var validation =
        new io.modelcontextprotocol.json.schema.jackson3.JacksonJsonSchemaValidatorSupplier()
            .get()
            .validate(HopMcpServer.outputSchemaFor("hop_execution_status"), result);
    assertTrue(validation.valid(), validation.errorMessage());
  }
}
