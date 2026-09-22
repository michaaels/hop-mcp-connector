package io.github.michaaels.hop.mcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** A bounded, read-only client for a configured Apache Hop Web base URL. */
final class HopWebClient {
  static final int MAX_HTTP_READ_BYTES = ProjectFiles.MAX_HTTP_READ_BYTES;
  static final int MAX_RESPONSE_READ_BYTES = MAX_HTTP_READ_BYTES;
  static final int MAX_WEB_BODY_RETURN_BYTES = ProjectFiles.MAX_WEB_BODY_RETURN_BYTES;
  private static final int MAX_HEADERS = 32;
  private static final int MAX_RESPONSE_HEADER_VALUE_BYTES = 1024;
  private static final Set<String> ALLOWED_METHODS = Set.of("GET", "HEAD");
  private static final Set<String> BLOCKED_HEADERS =
      Set.of("authorization", "cookie", "host", "content-length", "proxy-authorization");
  private static final Set<String> SENSITIVE_RESPONSE_HEADERS =
      Set.of("set-cookie", "authorization", "proxy-authenticate", "www-authenticate");

  private final URI baseUri;
  private final String username;
  private final String password;
  private final String bearerToken;
  private final Duration timeout;
  private final HttpClient client;

  HopWebClient(
      String baseUrl, String username, String password, String bearerToken, int timeoutSeconds) {
    if (timeoutSeconds < 1 || timeoutSeconds > 120) {
      throw new IllegalArgumentException("web timeout must be between 1 and 120 seconds");
    }
    this.baseUri = normalizeBaseUri(baseUrl);
    this.username = blankToNull(username);
    this.password = blankToNull(password);
    this.bearerToken = blankToNull(bearerToken);
    this.timeout = Duration.ofSeconds(timeoutSeconds);
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  Map<String, Object> request(String method, String path, Map<String, String> headers)
      throws IOException, InterruptedException {
    String requestMethod = normalizeMethod(method);
    URI uri = resolve(path);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .method(requestMethod, HttpRequest.BodyPublishers.noBody());

    int headerCount = 0;
    if (headers != null) {
      for (Map.Entry<String, String> entry : headers.entrySet()) {
        if (++headerCount > MAX_HEADERS) {
          throw new IllegalArgumentException("REST request has too many headers");
        }
        String name = entry.getKey();
        if (name == null
            || name.isBlank()
            || name.length() > 128
            || BLOCKED_HEADERS.contains(name.toLowerCase(Locale.ROOT))
            || SensitiveData.isSensitiveKey(name)) {
          throw new IllegalArgumentException("REST request contains a forbidden header");
        }
        if (entry.getValue() == null) {
          throw new IllegalArgumentException("REST request contains a null header value");
        }
        if (entry.getValue().length() > 2048)
          throw new IllegalArgumentException("REST header value exceeds 2048 characters");
        builder.header(name, entry.getValue());
      }
    }
    addAuthentication(builder);

    HttpResponse<InputStream> response =
        client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    try (InputStream responseStream = response.body()) {
      ReadResult body = readResponse(responseStream);
      Map<String, Object> output = new LinkedHashMap<>();
      output.put("method", requestMethod);
      output.put("url", publicUrl(uri));
      output.put("query_present", uri.getRawQuery() != null);
      output.put("status", response.statusCode());
      output.put("ok", response.statusCode() >= 200 && response.statusCode() < 300);
      output.put("headers", responseHeaders(response.headers().map()));
      BoundedText returnedBody =
          truncateUtf8(SensitiveData.redactSensitiveText(body.text()), MAX_WEB_BODY_RETURN_BYTES);
      output.put("body", returnedBody.text());
      output.put("body_bytes", body.bytes());
      output.put("returned_bytes", returnedBody.bytes());
      output.put("body_truncated", body.truncated() || returnedBody.truncated());
      return output;
    }
  }

  String baseUrl() {
    return baseUri.toString();
  }

  private void addAuthentication(HttpRequest.Builder builder) {
    if (bearerToken != null) {
      builder.header("Authorization", "Bearer " + bearerToken);
    } else if (username != null) {
      String credentials = username + ":" + (password == null ? "" : password);
      String encoded =
          Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
      builder.header("Authorization", "Basic " + encoded);
    }
  }

  private URI resolve(String path) {
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("REST path is required");
    }
    if (path.length() > 2048)
      throw new IllegalArgumentException("REST path exceeds 2048 characters");
    URI requested;
    try {
      requested = URI.create(path.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("REST path is not a valid URI", e);
    }
    if (requested.isAbsolute() || requested.getFragment() != null) {
      throw new IllegalArgumentException(
          "REST path must be relative and cannot contain a fragment");
    }
    if (requested.getRawAuthority() != null) {
      throw new IllegalArgumentException("REST path cannot contain an authority");
    }
    String rawRequestedPath = requested.getRawPath();
    if (rawRequestedPath != null) {
      String lowerRawPath = rawRequestedPath.toLowerCase(Locale.ROOT);
      if (lowerRawPath.contains("%2e")
          || lowerRawPath.contains("%2f")
          || lowerRawPath.contains("%5c")) {
        throw new IllegalArgumentException(
            "REST path contains an encoded traversal segment or separator");
      }
    }
    String requestedPath = requested.getPath();
    if (requestedPath == null || requestedPath.isBlank()) {
      requestedPath = "/";
    }
    String lowerRequestedPath = requestedPath.toLowerCase(Locale.ROOT);
    if (lowerRequestedPath.contains("%2e")
        || lowerRequestedPath.contains("%2f")
        || lowerRequestedPath.contains("%5c")) {
      throw new IllegalArgumentException(
          "REST path contains an encoded traversal segment or separator");
    }
    String basePath = baseUri.getPath();
    String suffix = requestedPath.startsWith("/") ? requestedPath.substring(1) : requestedPath;
    String joined = "/".equals(basePath) ? "/" + suffix : basePath + "/" + suffix;
    URI normalized;
    try {
      normalized =
          new URI(
                  baseUri.getScheme(),
                  baseUri.getRawAuthority(),
                  joined,
                  requested.getRawQuery(),
                  null)
              .normalize();
    } catch (Exception e) {
      throw new IllegalArgumentException("REST path is not a valid URI", e);
    }
    if (!isInsideBasePath(normalized.getPath(), basePath)) {
      throw new IllegalArgumentException("REST path escapes the configured Hop Web base path");
    }
    return normalized;
  }

  private static URI normalizeBaseUri(String baseUrl) {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("Hop Web URL is required");
    }
    URI uri;
    try {
      uri = URI.create(baseUrl.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Hop Web URL is not a valid URI", e);
    }
    String scheme = uri.getScheme();
    if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null) {
      throw new IllegalArgumentException(
          "Hop Web URL must be an http(s) URL without credentials, query or fragment");
    }
    String path = uri.getPath();
    if (path == null || path.isBlank()) {
      path = "/";
    }
    while (path.length() > 1 && path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    try {
      return new URI(
          uri.getScheme().toLowerCase(Locale.ROOT), uri.getRawAuthority(), path, null, null);
    } catch (Exception e) {
      throw new IllegalArgumentException("Hop Web URL is not a valid URI", e);
    }
  }

  private static boolean isInsideBasePath(String path, String basePath) {
    return "/".equals(basePath)
        ? path.startsWith("/")
        : path.equals(basePath) || path.startsWith(basePath + "/");
  }

  private static String normalizeMethod(String method) {
    String value = method == null || method.isBlank() ? "GET" : method.toUpperCase(Locale.ROOT);
    if (!ALLOWED_METHODS.contains(value)) {
      throw new SecurityException("Only GET and HEAD Hop Web requests are allowed");
    }
    return value;
  }

  private static Map<String, Object> responseHeaders(Map<String, List<String>> headers) {
    Map<String, Object> output = new LinkedHashMap<>();
    int count = 0;
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      if (++count > MAX_HEADERS) {
        break;
      }
      String key = entry.getKey();
      if (key == null) {
        continue;
      }
      String lower = key.toLowerCase(Locale.ROOT);
      output.put(
          key,
          isSensitiveResponseHeader(lower) ? "[REDACTED]" : responseHeaderValue(entry.getValue()));
    }
    return output;
  }

  private static String responseHeaderValue(List<String> values) {
    StringBuilder output = new StringBuilder();
    int outputBytes = 0;
    int valueCount = 0;
    boolean firstValue = true;
    for (String value : values) {
      if (value == null || ++valueCount > MAX_HEADERS) {
        return "[TRUNCATED]";
      }
      BoundedText boundedValue = truncateUtf8(value, MAX_RESPONSE_HEADER_VALUE_BYTES);
      if (boundedValue.truncated()) {
        return "[TRUNCATED]";
      }
      String redactedValue = HopXml.redact(boundedValue.text());
      int separatorBytes = firstValue ? 0 : 2;
      BoundedText boundedRedacted =
          truncateUtf8(
              redactedValue, MAX_RESPONSE_HEADER_VALUE_BYTES - outputBytes - separatorBytes);
      if (boundedRedacted.truncated()) {
        return "[TRUNCATED]";
      }
      if (separatorBytes > 0) {
        output.append(", ");
      }
      output.append(redactedValue);
      outputBytes += separatorBytes + boundedRedacted.bytes();
      firstValue = false;
    }
    return output.toString();
  }

  private static boolean isSensitiveResponseHeader(String lower) {
    return SENSITIVE_RESPONSE_HEADERS.contains(lower) || SensitiveData.isSensitiveKey(lower);
  }

  private static ReadResult readResponse(InputStream input) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int total = 0;
    while (total < MAX_RESPONSE_READ_BYTES) {
      int count = input.read(buffer, 0, Math.min(buffer.length, MAX_RESPONSE_READ_BYTES - total));
      if (count < 0) {
        return new ReadResult(output.toString(StandardCharsets.UTF_8), total, false);
      }
      output.write(buffer, 0, count);
      total += count;
    }
    // Stop at the network bound; a response ending exactly there is conservatively marked
    // truncated.
    return new ReadResult(output.toString(StandardCharsets.UTF_8), total, true);
  }

  private static BoundedText truncateUtf8(String text, int maxBytes) {
    int end = 0;
    int bytes = 0;
    while (end < text.length()) {
      int codePoint = text.codePointAt(end);
      int codePointBytes =
          codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
      if (bytes + codePointBytes > maxBytes) {
        return new BoundedText(text.substring(0, end), true, bytes);
      }
      bytes += codePointBytes;
      end += Character.charCount(codePoint);
    }
    return new BoundedText(text, false, bytes);
  }

  private static String publicUrl(URI uri) {
    try {
      return new URI(uri.getScheme(), uri.getRawAuthority(), uri.getPath(), null, null).toString();
    } catch (Exception e) {
      return baseOnly(uri);
    }
  }

  private static String baseOnly(URI uri) {
    return uri.getScheme() + "://" + uri.getRawAuthority();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private record ReadResult(String text, int bytes, boolean truncated) {}

  private record BoundedText(String text, boolean truncated, int bytes) {}
}
