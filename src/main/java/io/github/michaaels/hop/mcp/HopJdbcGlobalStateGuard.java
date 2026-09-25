package io.github.michaaels.hop.mcp;

import java.sql.DriverManager;
import java.util.Objects;
import java.util.concurrent.Callable;

/** Restores DriverManager's JVM-global login timeout after a bounded JDBC operation. */
final class HopJdbcGlobalStateGuard {
  private HopJdbcGlobalStateGuard() {}

  static synchronized <T> T call(Callable<T> operation) throws Exception {
    Objects.requireNonNull(operation, "operation");
    int previousLoginTimeout = DriverManager.getLoginTimeout();
    try {
      return operation.call();
    } finally {
      DriverManager.setLoginTimeout(previousLoginTimeout);
    }
  }
}
