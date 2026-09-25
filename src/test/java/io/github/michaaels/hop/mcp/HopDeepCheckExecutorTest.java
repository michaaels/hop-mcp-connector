package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class HopDeepCheckExecutorTest {
  @Test
  void timedOutStuckWorkerStaysDegradedUntilWorkEndsThenRecovers() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Future<String> stuck =
        HopDeepCheckExecutor.submit(
            () -> {
              started.countDown();
              awaitIgnoringInterrupt(release);
              return "finished";
            });
    try {
      assertTrue(started.await(5, TimeUnit.SECONDS));
      long completedBefore = number(HopDeepCheckExecutor.metrics(), "completed");
      HopDeepCheckExecutor.cancel(stuck, true);
      assertEquals("DEGRADED", HopDeepCheckExecutor.metrics().get("health"));

      Future<String> queued = HopDeepCheckExecutor.submit(() -> "after-stuck-work");
      assertEquals(1L, number(HopDeepCheckExecutor.metrics(), "queued"));
      release.countDown();
      assertEquals("after-stuck-work", queued.get(5, TimeUnit.SECONDS));

      Map<String, Object> recovered = HopDeepCheckExecutor.metrics();
      assertEquals("HEALTHY", recovered.get("health"));
      assertEquals(0L, number(recovered, "active"));
      assertEquals(0L, number(recovered, "queued"));
      assertTrue(number(recovered, "completed") >= completedBefore + 2);
      assertTrue(number(recovered, "timeouts") >= 1);
    } finally {
      release.countDown();
    }
  }

  @Test
  void queueCleanupAndSaturationAreBounded() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Future<String> active =
        HopDeepCheckExecutor.submit(
            () -> {
              started.countDown();
              release.await();
              return "active-finished";
            });
    List<Future<Integer>> queued = new ArrayList<>();
    try {
      assertTrue(started.await(5, TimeUnit.SECONDS));
      for (int i = 0; i < HopDeepCheckExecutor.MAX_QUEUED_CHECKS; i++) {
        queued.add(HopDeepCheckExecutor.submit(() -> 1));
      }
      assertEquals(
          (long) HopDeepCheckExecutor.MAX_QUEUED_CHECKS,
          number(HopDeepCheckExecutor.metrics(), "queued"));
      McpException busy =
          assertThrows(McpException.class, () -> HopDeepCheckExecutor.submit(() -> 2));
      assertEquals("DEEP_CHECK_BUSY", busy.code());

      for (Future<Integer> task : queued) HopDeepCheckExecutor.cancel(task, false);
      assertEquals(0L, number(HopDeepCheckExecutor.metrics(), "queued"));
      assertTrue(queued.stream().allMatch(Future::isCancelled));

      release.countDown();
      assertEquals("active-finished", active.get(5, TimeUnit.SECONDS));
      assertEquals("HEALTHY", HopDeepCheckExecutor.metrics().get("health"));
      assertTrue(number(HopDeepCheckExecutor.metrics(), "rejected") >= 1);
    } finally {
      release.countDown();
      for (Future<Integer> task : queued) HopDeepCheckExecutor.cancel(task, false);
    }
  }

  private static long number(Map<String, Object> metrics, String key) {
    return ((Number) metrics.get(key)).longValue();
  }

  private static void awaitIgnoringInterrupt(CountDownLatch release) {
    while (release.getCount() > 0) {
      try {
        release.await();
      } catch (InterruptedException ignored) {
        // Simulates an external JDBC call that does not honor cancellation.
      }
    }
  }
}
