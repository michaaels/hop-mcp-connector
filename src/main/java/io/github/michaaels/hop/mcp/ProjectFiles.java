package io.github.michaaels.hop.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class ProjectFiles {
  static final long MAX_FILE_BYTES = 4L * 1024 * 1024;
  static final int MAX_HTTP_READ_BYTES = 4 * 1024 * 1024;
  static final long MAX_READ_BYTES = MAX_FILE_BYTES;
  static final long MAX_REDACTED_FILE_BYTES = MAX_FILE_BYTES * 8;
  static final long MAX_TOTAL_SCAN_BYTES = 32L * 1024 * 1024;
  static final int MAX_RESPONSE_BYTES = 512 * 1024;
  static final int MAX_LOG_BYTES = 256 * 1024;
  static final int MAX_LOG_EVENTS = 50;
  static final int MAX_LOG_MESSAGE_CHARS = 1024;
  static final int MAX_WEB_BODY_RETURN_BYTES = 64 * 1024;
  static final int DEFAULT_TEXT_RESPONSE_BYTES = 64 * 1024;
  static final int MAX_TEXT_RESPONSE_BYTES = 128 * 1024;
  static final int MAX_RESPONSE_STRING_LENGTH = 8 * 1024;
  static final int MAX_STRUCTURED_RESULTS = 200;
  static final int MAX_SCAN_FILES = BoundedProjectWalker.MAX_RESULTS;
  static final int MAX_REGULAR_FILES_EXAMINED = BoundedProjectWalker.MAX_REGULAR_FILES_EXAMINED;
  static final int MAX_RESULTS = MAX_STRUCTURED_RESULTS;

  private record BoundedRead(byte[] bytes, boolean sourceChanged) {}

  private final Path root;

  ProjectFiles(Path root) throws IOException {
    Path absolute = root.toAbsolutePath().normalize();
    if (!Files.isDirectory(absolute))
      throw new IOException("Project root is not a directory: " + absolute);
    this.root = absolute.toRealPath();
  }

  Path root() {
    return root;
  }

  Path resolve(String relative) throws IOException {
    if (relative == null || relative.isBlank())
      throw new IllegalArgumentException("path is required");
    Path candidate = root.resolve(relative).normalize();
    rejectInternalPath(candidate);
    if (!candidate.startsWith(root))
      throw McpException.security(
          "PATH_OUTSIDE_PROJECT", "Path must remain under the configured project root.");
    if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS))
      throw new java.nio.file.NoSuchFileException(relative);
    Path real = candidate.toRealPath();
    if (!real.startsWith(root))
      throw McpException.security(
          "PATH_OUTSIDE_PROJECT", "Resolved path must remain under the configured project root.");
    return real;
  }

  String relative(Path p) {
    return root.relativize(p).toString().replace('\\', '/');
  }

  String readText(String relative) throws IOException {
    Path p = resolve(relative);
    if (!Files.isRegularFile(p)) throw new IOException("Not a regular file: " + relative);
    long size = Files.size(p);
    if (size > MAX_READ_BYTES) throw new IOException("File exceeds read limit: " + size + " bytes");
    return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(readBytes(p))).toString();
  }

  List<Path> definitions() throws IOException {
    return definitionScan(MAX_SCAN_FILES).files().stream()
        .map(BoundedProjectWalker.ScannedFile::path)
        .toList();
  }

  Map<String, Object> definitionsPage(int offset, int limit) throws IOException {
    validatePage(offset, limit, MAX_SCAN_FILES);
    BoundedProjectWalker.ScanResult scan = definitionScan(MAX_SCAN_FILES);
    List<BoundedProjectWalker.ScannedFile> paths = scan.files();
    int from = Math.min(offset, paths.size());
    int to = Math.min(from + limit, paths.size());
    List<Map<String, Object>> definitions = new ArrayList<>();
    for (BoundedProjectWalker.ScannedFile scannedFile : paths.subList(from, to)) {
      Path path = scannedFile.path();
      String relative = relative(path);
      definitions.add(
          Map.of(
              "path",
              relative,
              "type",
              relative.toLowerCase(Locale.ROOT).endsWith(".hpl") ? "pipeline" : "workflow"));
    }
    boolean moreKnown = to < paths.size();
    boolean truncated = scan.resultsTruncated() || scan.scanLimitReached() || moreKnown;
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("offset", offset);
    result.put("limit", limit);
    result.put("scanned", scan.regularFilesExamined());
    result.put("regular_files_examined", scan.regularFilesExamined());
    result.put("visited", scan.visitedEntries());
    result.put("scan_limit_reached", scan.scanLimitReached());
    result.put("results_truncated", truncated);
    result.put("count", paths.size());
    result.put("count_complete", !scan.scanLimitReached() && !scan.resultsTruncated());
    result.put("returned", definitions.size());
    result.put("has_more", moreKnown || scan.scanLimitReached() || scan.resultsTruncated());
    result.put("definitions", definitions);
    return result;
  }

  Map<String, Object> catalog(String glob, int offset, int limit) throws IOException {
    validatePage(offset, limit, MAX_SCAN_FILES);
    Pattern filter = globToPattern(glob == null || glob.isBlank() ? "**" : glob);
    BoundedProjectWalker.ScanResult scan =
        BoundedProjectWalker.scan(
            root,
            root.resolve(HopLiveUiEventBroker.CONTROL_DIRECTORY),
            path -> filter.matcher(relative(path)).matches(),
            MAX_SCAN_FILES);
    List<BoundedProjectWalker.ScannedFile> matches = scan.files();
    int from = Math.min(offset, matches.size());
    int to = Math.min(from + limit, matches.size());
    List<Map<String, Object>> entries = new ArrayList<>();
    long hashedBytes = 0;
    boolean scanLimitReached = scan.scanLimitReached();
    boolean hashBudgetExhausted = false;
    for (BoundedProjectWalker.ScannedFile scannedFile : matches.subList(from, to)) {
      Path path = scannedFile.path();
      Map<String, Object> entry = new LinkedHashMap<>();
      String relative = relative(path);
      long size = scannedFile.size();
      entry.put("path", relative);
      entry.put("kind", fileKind(relative));
      entry.put("extension", extension(relative));
      entry.put("bytes", size);
      entry.put("last_modified_epoch_ms", scannedFile.lastModified().toMillis());
      if (hashBudgetExhausted) {
        entry.put("sha256", "");
        entry.put("hash_skipped", true);
        entry.put("hash_skip_reason", "catalog hash scan byte limit reached");
        scanLimitReached = true;
      } else if (size <= MAX_READ_BYTES
          && size <= MAX_TOTAL_SCAN_BYTES - hashedBytes
          && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
        try {
          BoundedRead read = readBoundedBytes(path, MAX_TOTAL_SCAN_BYTES - hashedBytes);
          hashedBytes += read.bytes().length;
          if (read.sourceChanged()) {
            entry.put("sha256", "");
            entry.put("hash_skipped", true);
            entry.put("hash_skip_reason", "file changed during catalog scan");
            scanLimitReached = true;
            hashBudgetExhausted = true;
          } else {
            entry.put("sha256", sha256(read.bytes()));
            entry.put("hash_skipped", false);
            hashBudgetExhausted = hashedBytes >= MAX_TOTAL_SCAN_BYTES;
          }
        } catch (IOException | RuntimeException ignored) {
          entry.put("sha256", "");
          entry.put("hash_skipped", true);
          entry.put("hash_skip_reason", "file changed during catalog scan");
          scanLimitReached = true;
          hashBudgetExhausted = true;
        }
      } else {
        entry.put("sha256", "");
        entry.put("hash_skipped", true);
        entry.put(
            "hash_skip_reason",
            size > MAX_READ_BYTES
                ? "file exceeds read limit"
                : "catalog hash scan byte limit reached");
        if (size <= MAX_READ_BYTES) {
          scanLimitReached = true;
          hashBudgetExhausted = size > MAX_TOTAL_SCAN_BYTES - hashedBytes;
        }
      }
      entries.add(entry);
    }
    boolean moreKnown = to < matches.size();
    boolean resultsTruncated = scan.resultsTruncated() || scan.scanLimitReached() || moreKnown;
    boolean countComplete = !scanLimitReached && !scan.resultsTruncated();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("glob", glob == null || glob.isBlank() ? "**" : glob);
    result.put("offset", offset);
    result.put("limit", limit);
    result.put("scanned", scan.regularFilesExamined());
    result.put("regular_files_examined", scan.regularFilesExamined());
    result.put("visited", scan.visitedEntries());
    result.put("scan_limit_reached", scanLimitReached);
    result.put("count", matches.size());
    result.put("count_complete", countComplete);
    result.put("returned", entries.size());
    result.put("scanned_bytes", hashedBytes);
    result.put("results_truncated", resultsTruncated);
    result.put("has_more", moreKnown || scanLimitReached || scan.resultsTruncated());
    result.put("files", entries);
    return result;
  }

  Map<String, Object> search(String query, String glob, int offset, int limit) throws IOException {
    if (query == null || query.isBlank()) throw new IOException("query is required");
    if (query.length() > 256) throw new IllegalArgumentException("query exceeds 256 characters");
    validatePage(offset, limit, MAX_SCAN_FILES);
    String needle = query.toLowerCase(Locale.ROOT);
    Pattern filter = globToPattern(glob == null || glob.isBlank() ? "**" : glob);
    List<Map<String, Object>> out = new ArrayList<>();
    BoundedProjectWalker.ScanResult scan =
        BoundedProjectWalker.scan(
            root,
            root.resolve(HopLiveUiEventBroker.CONTROL_DIRECTORY),
            path -> filter.matcher(relative(path)).matches(),
            MAX_SCAN_FILES);
    int matched = 0;
    int scannedFiles = 0;
    long scannedBytes = 0;
    boolean scanLimitReached = scan.scanLimitReached();
    boolean resultLimitReached = false;
    searchFiles:
    for (BoundedProjectWalker.ScannedFile scannedFile : scan.files()) {
      Path path = scannedFile.path();
      long size = scannedFile.size();
      long remainingBytes = MAX_TOTAL_SCAN_BYTES - scannedBytes;
      if (size > MAX_READ_BYTES || size > remainingBytes) {
        scanLimitReached = true;
        continue;
      }
      scannedFiles++;
      String relative = relative(path);
      BoundedRead read;
      try {
        read = readBoundedBytes(path, remainingBytes);
        scannedBytes += read.bytes().length;
        if (read.sourceChanged()) {
          scanLimitReached = true;
          break;
        }
      } catch (IOException | RuntimeException ignored) {
        scanLimitReached = true;
        break;
      }
      try {
        String text =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(read.bytes()))
                .toString();
        text = SensitiveData.redactSensitiveText(text);
        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
          String line;
          int lineNumber = 0;
          while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (!line.toLowerCase(Locale.ROOT).contains(needle)) continue;
            matched++;
            if (matched > offset && out.size() < limit) {
              out.add(
                  Map.of(
                      "path",
                      relative,
                      "line",
                      lineNumber,
                      "text",
                      truncate(SensitiveData.redactText(line), 500)));
            } else if (matched > offset + limit) {
              resultLimitReached = true;
              break searchFiles;
            }
          }
        }
      } catch (IOException | RuntimeException ignored) {
        scanLimitReached = true;
      }
    }
    boolean resultsTruncated = resultLimitReached || scanLimitReached || scan.resultsTruncated();
    boolean countComplete = !resultsTruncated && !scanLimitReached;
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("query", query);
    result.put("glob", glob == null || glob.isBlank() ? "**" : glob);
    result.put("offset", offset);
    result.put("limit", limit);
    result.put("count", matched);
    result.put("count_complete", countComplete);
    result.put("returned", out.size());
    result.put("scanned_files", scannedFiles);
    result.put("visited_entries", scan.visitedEntries());
    result.put("scanned_bytes", scannedBytes);
    result.put("scan_limit_reached", scanLimitReached);
    result.put("result_limit_reached", resultLimitReached);
    result.put("results_truncated", resultsTruncated);
    result.put("has_more", resultLimitReached || scanLimitReached || scan.resultsTruncated());
    result.put("results", out);
    return result;
  }

  Map<String, Object> readTextChunk(String relative, long offset, int maxBytes) throws IOException {
    validateTextChunk(offset, maxBytes);
    return textChunk(relative, readText(relative), offset, maxBytes);
  }

  Map<String, Object> textChunk(String relative, String text, long offset, int maxBytes) {
    validateTextChunk(offset, maxBytes);
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    long totalBytes = bytes.length;
    if (offset > totalBytes) throw new IllegalArgumentException("offset exceeds text size");
    int start = Math.toIntExact(offset);
    if (start < bytes.length && (bytes[start] & 0xc0) == 0x80)
      throw new IllegalArgumentException("offset must be a UTF-8 code point boundary");
    int safeLength = Math.min(maxBytes, bytes.length - start);
    if (start + safeLength < bytes.length) {
      int boundary = start + safeLength;
      while (boundary > start && (bytes[boundary] & 0xc0) == 0x80) boundary--;
      if (boundary < start + safeLength && (bytes[boundary] & 0xc0) == 0xc0)
        safeLength = boundary - start;
    }
    long nextOffset = offset + safeLength;
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("path", relative);
    result.put("offset", offset);
    result.put("returned_bytes", safeLength);
    result.put("total_bytes", totalBytes);
    result.put("truncated", nextOffset < totalBytes);
    result.put("next_offset", nextOffset);
    result.put("eof", nextOffset >= totalBytes);
    result.put("text", new String(bytes, start, safeLength, StandardCharsets.UTF_8));
    return result;
  }

  static void validateTextChunk(long offset, int maxBytes) {
    if (offset < 0 || offset > MAX_REDACTED_FILE_BYTES)
      throw new IllegalArgumentException("offset must be between 0 and " + MAX_REDACTED_FILE_BYTES);
    if (maxBytes < 4 || maxBytes > MAX_TEXT_RESPONSE_BYTES)
      throw new IllegalArgumentException(
          "max_bytes must be between 4 and " + MAX_TEXT_RESPONSE_BYTES);
  }

  BoundedProjectWalker.ScanResult definitionScan(int resultLimit) throws IOException {
    return BoundedProjectWalker.scan(
        root,
        root.resolve(HopLiveUiEventBroker.CONTROL_DIRECTORY),
        path -> {
          String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
          return name.endsWith(".hpl") || name.endsWith(".hwf");
        },
        resultLimit);
  }

  private static void validatePage(int offset, int limit, int maxOffset) {
    if (offset < 0 || offset > maxOffset)
      throw new IllegalArgumentException("offset must be between 0 and " + maxOffset);
    if (limit < 1 || limit > MAX_STRUCTURED_RESULTS)
      throw new IllegalArgumentException("limit must be between 1 and " + MAX_STRUCTURED_RESULTS);
  }

  Path resolveForWrite(String relative) throws IOException {
    if (relative == null || relative.isBlank())
      throw new IllegalArgumentException("path is required");
    Path candidate = root.resolve(relative).normalize();
    rejectInternalPath(candidate);
    if (!candidate.startsWith(root))
      throw McpException.security(
          "PATH_OUTSIDE_PROJECT", "Path must remain under the configured project root.");
    Path parent = candidate.getParent();
    if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))
      throw new java.nio.file.NoSuchFileException(relative);
    Path realParent = parent.toRealPath();
    if (!realParent.startsWith(root))
      throw McpException.security(
          "PATH_OUTSIDE_PROJECT", "Resolved parent must remain under the configured project root.");
    if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
      if (Files.isSymbolicLink(candidate)
          || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
        throw new IOException("Not a regular file: " + relative);
      Path real = candidate.toRealPath();
      if (!real.startsWith(root))
        throw McpException.security(
            "PATH_OUTSIDE_PROJECT", "Resolved path must remain under the configured project root.");
    }
    return candidate;
  }

  byte[] readBytes(Path path) throws IOException {
    return readBytes(path, MAX_FILE_BYTES);
  }

  byte[] readBytes(Path path, long maximumBytes) throws IOException {
    BoundedRead read = readBoundedBytes(path, maximumBytes);
    if (read.sourceChanged()) throw new IOException("File changed while reading");
    return read.bytes();
  }

  private BoundedRead readBoundedBytes(Path path, long maximumBytes) throws IOException {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("Not a regular file");
    long maximum = Math.min(MAX_FILE_BYTES, maximumBytes);
    if (maximum < 0) throw new IOException("Read limit must be zero or greater");
    long sizeBefore = Files.size(path);
    if (sizeBefore > maximum)
      throw new IOException("File exceeds read limit: " + sizeBefore + " bytes");
    try (InputStream stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      byte[] bytes = stream.readNBytes(Math.toIntExact(maximum));
      long sizeAfter = Files.size(path);
      return new BoundedRead(bytes, sizeBefore != sizeAfter || bytes.length != sizeAfter);
    }
  }

  private void rejectInternalPath(Path path) throws IOException {
    if (isInternalPath(path))
      throw McpException.security(
          "INTERNAL_PATH_DENIED",
          "The internal connector control directory is not accessible through project tools.");
  }

  private boolean isInternalPath(Path path) {
    return path.toAbsolutePath()
        .normalize()
        .startsWith(root.resolve(HopLiveUiEventBroker.CONTROL_DIRECTORY));
  }

  static String sha256(byte[] bytes) throws IOException {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IOException("SHA-256 is unavailable", e);
    }
  }

  private static String extension(String path) {
    int dot = path.lastIndexOf('.');
    return dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  private static String fileKind(String path) {
    return switch (extension(path)) {
      case "hpl" -> "pipeline";
      case "hwf" -> "workflow";
      case "ktr" -> "kettle_pipeline";
      case "kjb" -> "kettle_workflow";
      case "csv" -> "csv";
      case "json", "xml", "yaml", "yml", "properties" -> "metadata";
      case "txt", "sql", "md", "log" -> "text";
      default -> "file";
    };
  }

  private static Pattern globToPattern(String glob) {
    if (glob == null || glob.length() > 256)
      throw new IllegalArgumentException("glob must contain at most 256 characters");
    int wildcardOperators = 0;
    for (int i = 0; i < glob.length(); i++) {
      char current = glob.charAt(i);
      if (current == '?' || current == '*') {
        wildcardOperators++;
        if (current == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') i++;
      }
    }
    if (wildcardOperators > 16)
      throw new IllegalArgumentException("glob cannot contain more than 16 wildcard operators");
    StringBuilder r = new StringBuilder("^");
    for (int i = 0; i < glob.length(); i++) {
      char c = glob.charAt(i);
      if (c == '*') {
        if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
          r.append(".*");
          i++;
        } else r.append("[^/]*");
      } else if (c == '?') r.append('.');
      else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) r.append('\\').append(c);
      else r.append(c == '\\' ? '/' : c);
    }
    return Pattern.compile(r.append('$').toString(), Pattern.CASE_INSENSITIVE);
  }

  static String truncate(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max) + "…";
  }
}
