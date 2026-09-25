package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.sql.DriverManager;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hop.core.database.DatabaseTestResults;
import org.apache.hop.core.variables.Variables;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock(value = "DriverManager.loginTimeout", mode = ResourceAccessMode.READ_WRITE)
class HopJdbcGlobalStateGuardTest {
  private int originalLoginTimeout;

  @BeforeEach
  void saveGlobalState() throws Exception {
    originalLoginTimeout = DriverManager.getLoginTimeout();
    DriverManager.setLoginTimeout(17);
  }

  @AfterEach
  void restoreGlobalState() throws Exception {
    DriverManager.setLoginTimeout(originalLoginTimeout);
  }

  @Test
  void restoresLoginTimeoutAfterSuccessAndException() throws Exception {
    String result =
        HopJdbcGlobalStateGuard.call(
            () -> {
              DriverManager.setLoginTimeout(91);
              return "ok";
            });
    assertEquals("ok", result);
    assertEquals(17, DriverManager.getLoginTimeout());

    try {
      HopJdbcGlobalStateGuard.call(
          () -> {
            DriverManager.setLoginTimeout(92);
            throw new IOException("expected");
          });
    } catch (IOException expected) {
      assertEquals("expected", expected.getMessage());
    }
    assertEquals(17, DriverManager.getLoginTimeout());
  }

  @Test
  void serializesPluginOwnedOperationsThatShareTheJvmGlobalTimeout() throws Exception {
    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch secondAttempted = new CountDownLatch(1);
    CountDownLatch secondEntered = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<String> first =
          pool.submit(
              () ->
                  HopJdbcGlobalStateGuard.call(
                      () -> {
                        DriverManager.setLoginTimeout(31);
                        firstEntered.countDown();
                        releaseFirst.await();
                        return "first";
                      }));
      assertTrue(firstEntered.await(5, TimeUnit.SECONDS));

      Future<String> second =
          pool.submit(
              () -> {
                secondAttempted.countDown();
                return HopJdbcGlobalStateGuard.call(
                    () -> {
                      secondEntered.countDown();
                      DriverManager.setLoginTimeout(32);
                      return "second";
                    });
              });
      assertTrue(secondAttempted.await(5, TimeUnit.SECONDS));
      assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS));

      releaseFirst.countDown();
      assertEquals("first", first.get(5, TimeUnit.SECONDS));
      assertEquals("second", second.get(5, TimeUnit.SECONDS));
      assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
      assertEquals(17, DriverManager.getLoginTimeout());
    } finally {
      releaseFirst.countDown();
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void restoresGlobalStateAfterTimeoutWhenTheProbeStopsOnInterruption() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch exited = new CountDownLatch(1);
    HopConnectionService service = blockingService(entered, exited);

    Map<String, Object> result = service.testConnection("DWH_PROD", 1);

    assertEquals("timeout", result.get("status"));
    assertTrue(entered.await(5, TimeUnit.SECONDS));
    assertTrue(exited.await(5, TimeUnit.SECONDS));
    assertEquals(17, loginTimeoutAfterWorkerFinishes());
  }

  @Test
  void restoresGlobalStateAfterCallerInterruption() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch exited = new CountDownLatch(1);
    HopConnectionService service = blockingService(entered, exited);
    AtomicBoolean callerObservedInterrupt = new AtomicBoolean();
    AtomicReference<Throwable> callerFailure = new AtomicReference<>();
    Thread caller =
        new Thread(
            () -> {
              try {
                service.testConnection("DWH_PROD", 10);
              } catch (InterruptedException expected) {
                callerObservedInterrupt.set(Thread.currentThread().isInterrupted());
              } catch (Throwable failure) {
                callerFailure.set(failure);
              }
            },
            "jdbc-guard-interruption-test");
    caller.start();
    assertTrue(entered.await(5, TimeUnit.SECONDS));
    caller.interrupt();
    caller.join(TimeUnit.SECONDS.toMillis(5));

    assertFalse(caller.isAlive());
    assertEquals(null, callerFailure.get());
    assertTrue(callerObservedInterrupt.get());
    assertTrue(exited.await(5, TimeUnit.SECONDS));
    assertEquals(17, loginTimeoutAfterWorkerFinishes());
  }

  private static HopConnectionService blockingService(
      CountDownLatch entered, CountDownLatch exited) {
    return new HopConnectionService(
        null,
        new Variables(),
        true,
        (database, variables) -> {
          DriverManager.setLoginTimeout(93);
          entered.countDown();
          try {
            new CountDownLatch(1).await();
            DatabaseTestResults result = new DatabaseTestResults();
            result.setSuccess(true);
            return result;
          } finally {
            exited.countDown();
          }
        },
        name -> null);
  }

  private static int loginTimeoutAfterWorkerFinishes() throws Exception {
    Future<Integer> observation = HopDeepCheckExecutor.submit(DriverManager::getLoginTimeout);
    return observation.get(5, TimeUnit.SECONDS);
  }
}
