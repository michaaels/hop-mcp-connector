package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hop.core.database.DatabaseTestResults;
import org.apache.hop.core.variables.Variables;
import org.junit.jupiter.api.Test;

class HopConnectionServiceTest {
  @Test
  void usesNativeProbeWithBoundedVariablesAndRedactsDiagnostics() throws Exception {
    AtomicReference<String> configuredTimeout = new AtomicReference<>();

    HopConnectionService service =
        new HopConnectionService(
            null,
            new Variables(),
            true,
            (database, variables) -> {
              configuredTimeout.set(variables.getVariable("HOP_DATABASE_CONNECTION_TIMEOUT"));
              DatabaseTestResults result = new DatabaseTestResults();
              result.setSuccess(false);
              result.setMessage("jdbc:postgresql://user:secret@example/db?password=second-secret");
              return result;
            },
            name -> null);

    Map<String, Object> result = service.testConnection("DWH_PROD", 3);

    assertEquals("3", configuredTimeout.get());
    assertEquals("failure", result.get("status"));
    assertFalse(String.valueOf(result.get("message")).contains("secret"));
    assertFalse(String.valueOf(result.get("message")).contains("second-secret"));
    assertEquals(true, result.get("redaction_applied"));
  }

  @Test
  void deniesConnectionTestingWithoutDeepCheckAuthorization() {
    HopConnectionService service = new HopConnectionService(null, new Variables(), false);

    assertThrows(
        SecurityException.class,
        () -> service.testConnection("DWH_PROD", HopConnectionService.DEFAULT_TIMEOUT_SECONDS));
  }

  @Test
  void validatesTimeoutBoundsBeforeContactingMetadata() {
    HopConnectionService service = new HopConnectionService(null, new Variables(), true);

    assertThrows(IllegalArgumentException.class, () -> service.testConnection("DWH_PROD", 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.testConnection("DWH_PROD", HopConnectionService.MAX_TIMEOUT_SECONDS + 1));
  }
}
