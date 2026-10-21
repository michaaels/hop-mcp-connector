package io.github.michaaels.hop.mcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** A bounded, read-only client for a configured Apache Hop Web base URL. */
final class HopWebClient {
  static final int MAX_HTTP_READ_BYTES = ProjectFiles.MAX_HTTP_READ_BYTES;
  static final int MAX_RESPONSE_READ_BYTES = MAX_HTTP_READ_BYTES;
  static final int MAX_WEB_BODY_RETURN_BYTES = ProjectFiles.MAX_WEB_BODY_RETURN_BYTES;
  private static final int MAX_HEADERS = 32;
  private static final int MAX_RESPONSE_HEADER_VALUE_BYTES = 1024;
  private static final Set<String> ALLOWED_METHODS = Set.of("GET", "HEAD");
  private static final Set<String> BLOCKED_HEADERS =
      Set.of(
          "authorization",
          "cookie",
          "host",
          "content-length",
          "proxy-authorization",
          "forwarded",
          "x-original-url",
          "x-original-uri",
          "x-rewrite-url",
          "x-http-method-override",
          "x-method-override",
          "x-envoy-original-path");
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
    HopWebReadPolicy.requireReadTarget(uri);
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
            || name.toLowerCase(Locale.ROOT).startsWith("x-forwarded-")
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

    HttpResponse<ReadResult> response = sendBounded(builder.build());
    ReadResult body = response.body();
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

  private HttpResponse<ReadResult> sendBounded(HttpRequest request)
      throws IOException, InterruptedException {
    BoundedBodySubscriber body = new BoundedBodySubscriber();
    long started = System.nanoTime();
    CompletableFuture<HttpResponse<ReadResult>> response = client.sendAsync(request, info -> body);
    try {
      long remaining = timeout.toNanos() - (System.nanoTime() - started);
      if (remaining <= 0) throw new TimeoutException();
      return response.get(remaining, TimeUnit.NANOSECONDS);
    } catch (TimeoutException expired) {
      throw new HttpTimeoutException("Hop Web response exceeded the configured timeout");
    } catch (ExecutionException failed) {
      Throwable cause = failed.getCause();
      if (cause instanceof IOException exception) throw exception;
      if (cause instanceof RuntimeException exception) throw exception;
      throw new IOException("Hop Web request failed", cause);
    } finally {
      body.cancel();
      response.cancel(true);
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
    HopWebReadPolicy.requireCanonicalPath(requested.getRawPath());
    String requestedPath = requested.getPath();
    if (requestedPath == null || requestedPath.isBlank()) {
      requestedPath = "/";
    }
    String basePath = baseUri.getPath();
    String suffix = requestedPath.startsWith("/") ? requestedPath.substring(1) : requestedPath;
    String joined = "/".equals(basePath) ? "/" + suffix : basePath + "/" + suffix;
    URI normalized;
    try {
      normalized =
          new URI(baseUri.getScheme(), baseUri.getRawAuthority(), joined, null, null).normalize();
    } catch (Exception e) {
      throw new IllegalArgumentException("REST path is not a valid URI", e);
    }
    if (!isInsideBasePath(normalized.getPath(), basePath)) {
      throw new IllegalArgumentException("REST path escapes the configured Hop Web base path");
    }
    String rawQuery = requested.getRawQuery();
    URI resolved =
        rawQuery == null ? normalized : URI.create(normalized.toASCIIString() + "?" + rawQuery);
    if (!resolved.getScheme().equals(baseUri.getScheme())
        || !resolved.getRawAuthority().equals(baseUri.getRawAuthority())
        || !isInsideBasePath(resolved.getPath(), basePath)) {
      throw new IllegalArgumentException("REST path escapes the configured Hop Web base path");
    }
    return resolved;
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
    HopWebReadPolicy.requireCanonicalPath(uri.getRawPath());
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

  /** Completes only at EOF or the byte bound, so the request deadline covers body reception. */
  private static final class BoundedBodySubscriber
      implements HttpResponse.BodySubscriber<ReadResult> {
    private final CompletableFuture<ReadResult> body = new CompletableFuture<>();
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final byte[] buffer = new byte[8192];
    private Flow.Subscription subscription;
    private boolean cancelled;

    @Override
    public CompletionStage<ReadResult> getBody() {
      return body;
    }

    @Override
    public synchronized void onSubscribe(Flow.Subscription incoming) {
      if (cancelled || subscription != null) {
        incoming.cancel();
        return;
      }
      subscription = incoming;
      incoming.request(1);
    }

    @Override
    public synchronized void onNext(List<ByteBuffer> buffers) {
      if (cancelled || body.isDone()) return;
      for (ByteBuffer bytes : buffers) {
        while (bytes.hasRemaining() && output.size() < MAX_RESPONSE_READ_BYTES) {
          int count =
              Math.min(
                  buffer.length,
                  Math.min(bytes.remaining(), MAX_RESPONSE_READ_BYTES - output.size()));
          bytes.get(buffer, 0, count);
          output.write(buffer, 0, count);
        }
        if (output.size() == MAX_RESPONSE_READ_BYTES) {
          // Exact-bound responses remain conservatively marked truncated, without an extra read.
          complete(true);
          cancel();
          return;
        }
      }
      subscription.request(1);
    }

    @Override
    public synchronized void onError(Throwable failure) {
      body.completeExceptionally(failure);
    }

    @Override
    public synchronized void onComplete() {
      complete(false);
    }

    private void complete(boolean truncated) {
      if (body.isDone()) return;
      body.complete(
          new ReadResult(output.toString(StandardCharsets.UTF_8), output.size(), truncated));
    }

    synchronized void cancel() {
      cancelled = true;
      if (subscription != null) subscription.cancel();
      body.cancel(false);
    }
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
