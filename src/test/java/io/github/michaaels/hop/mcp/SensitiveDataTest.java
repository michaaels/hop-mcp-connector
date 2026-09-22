package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class SensitiveDataTest {
  @Test
  void recognizesSensitiveKeyVariants() {
    for (String key :
        List.of(
            "credential",
            "credentials",
            "dbPassword",
            "passwd",
            "PWD",
            "accessToken",
            "Authorization",
            "auth_header",
            "client_secret",
            "apiKey",
            "x-api-key",
            "access_key",
            "private_key",
            "Bearer")) {
      assertTrue(SensitiveData.isSensitiveKey(key), key);
    }
    assertFalse(SensitiveData.isSensitiveKey("username"));
    assertFalse(SensitiveData.isSensitiveKey("description"));
    assertFalse(SensitiveData.isSensitiveKey(null));
  }

  @Test
  void redactsJsonXmlAndAuthorizationText() {
    String input =
        "{\"credential\":\"json-credential\",\"credentials\":\"json-credentials\","
            + "\"pwd\":\"json-pwd\",\"apiKey\":\"json-api-key\","
            + "\"nested\":{\"credentials\":{\"user\":\"json-nested-secret\"}}} "
            + "<root><password>xml-password</password><credentials>xml-credentials</credentials>"
            + "<connection private_key=\"xml-private-key\"/></root> "
            + "Authorization: Bearer bearer-value; Authorization: Basic basic-value "
            + "Bearer standalone-value";

    String redacted = SensitiveData.redactText(input);

    for (String secret :
        List.of(
            "json-credential",
            "json-credentials",
            "json-pwd",
            "json-api-key",
            "json-nested-secret",
            "xml-password",
            "xml-credentials",
            "xml-private-key",
            "bearer-value",
            "basic-value",
            "standalone-value")) {
      assertFalse(redacted.contains(secret), secret);
    }
    assertTrue(redacted.contains("<password>***REDACTED***</password>"));
    assertTrue(redacted.contains("***REDACTED***"));
  }

  @Test
  void redactsNestedMapAndListValuesWithoutChangingTheInput() {
    Map<String, Object> input =
        Map.of(
            "credentials",
            Map.of("username", "hop-user", "password", "map-password"),
            "items",
            List.of(Map.of("PWD", "list-password"), List.of("Authorization: Basic list-basic")));

    Object redacted = SensitiveData.redactValue(input);

    assertEquals(
        Map.of(
            "credentials",
            "***REDACTED***",
            "items",
            List.of(
                Map.of("PWD", "***REDACTED***"), List.of("Authorization: Basic ***REDACTED***"))),
        redacted);
    assertTrue(input.toString().contains("map-password"));
    assertFalse(redacted.toString().contains("map-password"));
    assertFalse(redacted.toString().contains("list-password"));
    assertFalse(redacted.toString().contains("list-basic"));
  }

  @Test
  void sanitizesExceptionsAndBoundsTextLength() {
    String longMessage = "diagnostic ".repeat(SensitiveData.MAX_SANITIZED_TEXT_LENGTH);
    String sanitized = SensitiveData.sanitizeExceptionMessage(longMessage);
    String secretMessage =
        SensitiveData.sanitizeExceptionMessage(
            new IllegalStateException("password=" + "exception-secret".repeat(500)));

    assertEquals(SensitiveData.MAX_SANITIZED_TEXT_LENGTH, sanitized.length());
    assertTrue(sanitized.endsWith("...[TRUNCATED]"));
    assertTrue(secretMessage.length() <= SensitiveData.MAX_SANITIZED_TEXT_LENGTH);
    assertFalse(secretMessage.contains("exception-secret"));
    assertNull(SensitiveData.sanitizeExceptionMessage((String) null));
  }

  @Test
  @Timeout(5)
  void redactsLargeUnstructuredTextWithoutRegexBacktracking() {
    String text = "a".repeat(1024 * 1024);

    assertEquals(text, SensitiveData.redactSensitiveText(text));
  }
}
