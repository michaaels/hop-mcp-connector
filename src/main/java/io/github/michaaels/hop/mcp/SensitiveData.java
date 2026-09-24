package io.github.michaaels.hop.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Common secret detection and redaction helpers for data returned by the connector. */
final class SensitiveData {
  static final int MAX_SANITIZED_TEXT_LENGTH = 4096;
  private static final int MAX_KEY_LENGTH = 128;

  static final String REDACTED = "***REDACTED***";
  private static final String SENSITIVE_XML_NAME =
      "[\\w:.-]*(?:password|passwd|pwd|token|secret|credential|authorization|auth[_-]?header|"
          + "bearer|client[_-]?secret|api[_-]?key|access[_-]?key|private[_-]?key)[\\w:.-]*";
  private static final Pattern SENSITIVE_XML_ELEMENT =
      Pattern.compile(
          "(?is)(<\\s*(" + SENSITIVE_XML_NAME + ")\\b[^>]*?(?<!/)>)" + ".*?(</\\s*\\2\\s*>|$)");
  private static final Pattern AUTH_SCHEME =
      Pattern.compile("(?i)\\b(Bearer|Basic)(\\s+)[^\\s,;\\\"']+");

  private record Assignment(String key, int valueStart, int nextPosition) {}

  private SensitiveData() {}

  static String redactedMarker() {
    return REDACTED;
  }

  static boolean isSensitiveKey(String key) {
    if (key == null || key.isBlank()) {
      return false;
    }
    String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    return normalized.equals("auth")
        || normalized.contains("password")
        || normalized.contains("passwd")
        || normalized.contains("pwd")
        || normalized.contains("token")
        || normalized.contains("secret")
        || normalized.contains("credential")
        || normalized.contains("bearer")
        || normalized.contains("authorization")
        || normalized.contains("authheader")
        || normalized.contains("apikey")
        || normalized.contains("accesskey")
        || normalized.contains("privatekey")
        || normalized.contains("cookie")
        || normalized.contains("authenticate")
        || normalized.endsWith("auth");
  }

  static String redactText(String text) {
    if (text == null) {
      return null;
    }
    return truncate(redactSensitiveText(text), MAX_SANITIZED_TEXT_LENGTH);
  }

  static String redactSensitiveText(String text) {
    if (text == null) {
      return null;
    }
    String redacted = redactXmlElements(text);
    redacted = redactKeyValueAssignments(redacted);
    return AUTH_SCHEME.matcher(redacted).replaceAll("$1$2" + REDACTED);
  }

  static Object redactValue(Object value) {
    if (value instanceof String text) {
      return redactSensitiveText(text);
    }
    if (value instanceof Throwable error) {
      return sanitizeExceptionMessage(error.getMessage());
    }
    if (value instanceof Map<?, ?> map) {
      Map<Object, Object> redacted = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        Object key = entry.getKey();
        redacted.put(
            key,
            key instanceof String name && isSensitiveKey(name)
                ? REDACTED
                : redactValue(entry.getValue()));
      }
      return redacted;
    }
    if (value instanceof List<?> list) {
      List<Object> redacted = new ArrayList<>(list.size());
      for (Object item : list) {
        redacted.add(redactValue(item));
      }
      return redacted;
    }
    return value;
  }

  static Map<String, Object> redactMap(Map<String, Object> value) {
    Map<String, Object> redacted = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : value.entrySet()) {
      String key = entry.getKey();
      redacted.put(key, isSensitiveKey(key) ? REDACTED : redactValue(entry.getValue()));
    }
    return redacted;
  }

  static String sanitizeExceptionMessage(String message) {
    if (message == null) {
      return null;
    }
    return redactText(message.replaceAll("[\\r\\n\\p{Cntrl}]", " "));
  }

  static String sanitizeExceptionMessage(Throwable error) {
    return error == null ? null : sanitizeExceptionMessage(error.getMessage());
  }

  private static String redactXmlElements(String text) {
    Matcher matcher = SENSITIVE_XML_ELEMENT.matcher(text);
    StringBuffer output = new StringBuffer();
    while (matcher.find()) {
      String content = matcher.group().substring(matcher.group(1).length());
      content = content.substring(0, content.length() - matcher.group(3).length());
      String lineBreaks = content.replaceAll("[^\\r\\n]", "");
      String replacement = matcher.group(1) + REDACTED + lineBreaks + matcher.group(3);
      matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(output);
    return output.toString();
  }

  private static String redactKeyValueAssignments(String text) {
    StringBuilder output = null;
    int copiedUntil = 0;
    int position = 0;
    while (position < text.length()) {
      Assignment assignment = assignmentAt(text, position);
      if (assignment == null) {
        position++;
        continue;
      }
      position = assignment.nextPosition();
      if (assignment.key().isEmpty() || !isSensitiveKey(assignment.key())) {
        continue;
      }
      int valueStart = assignment.valueStart();
      int valueEnd = valueEnd(text, valueStart, assignment.key());
      if (valueEnd <= valueStart) {
        continue;
      }
      if (output == null) output = new StringBuilder(text.length());
      output
          .append(text, copiedUntil, valueStart)
          .append(
              preserveLineBreaks(
                  text.substring(valueStart, valueEnd),
                  redactedAssignmentValue(text, valueStart, valueEnd, assignment.key())));
      copiedUntil = valueEnd;
      position = valueEnd;
    }
    return output == null ? text : output.append(text, copiedUntil, text.length()).toString();
  }

  private static Assignment assignmentAt(String text, int position) {
    char first = text.charAt(position);
    boolean quoted = first == '"' || first == '\'';
    int keyStart = quoted ? position + 1 : position;
    if (keyStart >= text.length() || !isKeyStart(text.charAt(keyStart))) return null;

    int keyEnd = keyStart;
    while (keyEnd < text.length() && isKeyCharacter(text.charAt(keyEnd))) keyEnd++;
    if (quoted && (keyEnd >= text.length() || text.charAt(keyEnd) != first))
      return new Assignment("", keyEnd, keyEnd);

    int separator = quoted ? keyEnd + 1 : keyEnd;
    while (separator < text.length() && Character.isWhitespace(text.charAt(separator))) separator++;
    if (separator >= text.length()
        || (text.charAt(separator) != ':' && text.charAt(separator) != '='))
      return new Assignment("", keyEnd, keyEnd);
    int valueStart = separator + 1;
    while (valueStart < text.length() && Character.isWhitespace(text.charAt(valueStart)))
      valueStart++;

    int keyLength = keyEnd - keyStart;
    String key = keyLength > MAX_KEY_LENGTH ? "" : text.substring(keyStart, keyEnd);
    return new Assignment(key, valueStart, valueStart);
  }

  private static boolean isKeyStart(char value) {
    return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z') || value == '_';
  }

  private static boolean isKeyCharacter(char value) {
    return isKeyStart(value) || (value >= '0' && value <= '9') || value == '.' || value == '-';
  }

  private static String redactedAssignmentValue(String text, int start, int end, String key) {
    if (isAuthorizationKey(key)) {
      char quote = text.charAt(start);
      boolean quoted = (quote == '"' || quote == '\'') && end > start + 1;
      int valueStart = quoted ? start + 1 : start;
      int valueEnd = quoted && text.charAt(end - 1) == quote ? end - 1 : end;
      int schemeEnd = wordEnd(text, valueStart);
      String scheme = text.substring(valueStart, schemeEnd);
      if (("bearer".equalsIgnoreCase(scheme) || "basic".equalsIgnoreCase(scheme))
          && schemeEnd < valueEnd
          && Character.isWhitespace(text.charAt(schemeEnd))) {
        int credentialStart = skipWhitespace(text, schemeEnd);
        if (credentialStart < valueEnd) {
          return (quoted ? String.valueOf(quote) : "")
              + text.substring(valueStart, credentialStart)
              + REDACTED
              + (quoted ? String.valueOf(quote) : "");
        }
      }
    }
    return "\"" + REDACTED + "\"";
  }

  private static int valueEnd(String text, int start, String key) {
    if (start >= text.length()) {
      return start;
    }
    char first = text.charAt(start);
    if (first == '"' || first == '\'') {
      return quotedValueEnd(text, start, first);
    }
    if (first == '{' || first == '[') {
      return compositeValueEnd(text, start);
    }
    if (isAuthorizationKey(key)) {
      int schemeEnd = wordEnd(text, start);
      String scheme = text.substring(start, schemeEnd);
      if (("bearer".equalsIgnoreCase(scheme) || "basic".equalsIgnoreCase(scheme))
          && schemeEnd < text.length()
          && Character.isWhitespace(text.charAt(schemeEnd))) {
        int credentialStart = skipWhitespace(text, schemeEnd);
        return scalarValueEnd(text, credentialStart);
      }
    }
    return scalarValueEnd(text, start);
  }

  private static int quotedValueEnd(String text, int start, char quote) {
    boolean escaped = false;
    for (int i = start + 1; i < text.length(); i++) {
      char current = text.charAt(i);
      if (escaped) {
        escaped = false;
      } else if (current == '\\') {
        escaped = true;
      } else if (current == quote) {
        return i + 1;
      }
    }
    return text.length();
  }

  private static int compositeValueEnd(String text, int start) {
    int depth = 0;
    char quote = 0;
    boolean escaped = false;
    for (int i = start; i < text.length(); i++) {
      char current = text.charAt(i);
      if (quote != 0) {
        if (escaped) {
          escaped = false;
        } else if (current == '\\') {
          escaped = true;
        } else if (current == quote) {
          quote = 0;
        }
      } else if (current == '"' || current == '\'') {
        quote = current;
      } else if (current == '{' || current == '[') {
        depth++;
      } else if (current == '}' || current == ']') {
        if (--depth == 0) {
          return i + 1;
        }
      }
    }
    return text.length();
  }

  private static int scalarValueEnd(String text, int start) {
    int end = start;
    while (end < text.length()) {
      char current = text.charAt(end);
      if (Character.isWhitespace(current)
          || current == ','
          || current == ';'
          || current == '}'
          || current == ']') {
        break;
      }
      end++;
    }
    return end;
  }

  private static int wordEnd(String text, int start) {
    int end = start;
    while (end < text.length() && Character.isLetter(text.charAt(end))) {
      end++;
    }
    return end;
  }

  private static int skipWhitespace(String text, int start) {
    int end = start;
    while (end < text.length() && Character.isWhitespace(text.charAt(end))) {
      end++;
    }
    return end;
  }

  private static String preserveLineBreaks(String original, String replacement) {
    String lineBreaks = original.replaceAll("[^\\r\\n]", "");
    if (lineBreaks.isEmpty()) {
      return replacement;
    }
    int insertAt =
        replacement.length() > 1
                && (replacement.charAt(replacement.length() - 1) == '"'
                    || replacement.charAt(replacement.length() - 1) == '\'')
            ? replacement.length() - 1
            : replacement.length();
    return replacement.substring(0, insertAt) + lineBreaks + replacement.substring(insertAt);
  }

  private static boolean isAuthorizationKey(String key) {
    String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    return normalized.equals("auth")
        || normalized.contains("authorization")
        || normalized.contains("authheader");
  }

  private static String truncate(String text, int maxLength) {
    if (text.length() <= maxLength) {
      return text;
    }
    String marker = "...[TRUNCATED]";
    int end = maxLength - marker.length();
    if (Character.isHighSurrogate(text.charAt(end - 1))
        && Character.isLowSurrogate(text.charAt(end))) {
      end--;
    }
    return text.substring(0, end) + marker;
  }
}
