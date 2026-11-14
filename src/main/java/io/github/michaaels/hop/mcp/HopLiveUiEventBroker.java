package io.github.michaaels.hop.mcp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Small, filesystem-backed event bridge between a headless MCP process and Hop UI processes that
 * share the same project directory.
 *
 * <p>The bridge intentionally carries only definition paths and transaction fingerprints. It does
 * not serialize Hop metadata, credentials, or user-provided component values.
 */
final class HopLiveUiEventBroker implements HopSemanticEventSink {
  static final String CONTROL_DIRECTORY = ".hop-mcp";
  static final Duration SESSION_TTL = Duration.ofSeconds(45);
  static final Duration EVENT_TTL = Duration.ofHours(24);
  static final int MAX_EVENT_FILES = 200;
  static final int MAX_ACKNOWLEDGEMENT_FILES = 400;
  static final int MAX_SESSION_FILES = 32;
  static final int MAX_CONTROL_FILE_BYTES = 16 * 1024;
  static final int MAX_PRESENTATION_FILES = 100;
  static final long MAX_DRAFT_STORAGE_BYTES = 32L * 1024 * 1024;

  private static final Pattern CLIENT_TYPE = Pattern.compile("[a-z0-9_-]{1,32}");
  private static final Pattern SHA_256 = Pattern.compile("[a-fA-F0-9]{64}");
  private static final Pattern UUID_PATTERN =
      Pattern.compile(
          "[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}");

  private final Path projectRoot;
  private final Path controlRoot;
  private final Path eventsDirectory;
  private final Path sessionsDirectory;
  private final Path acknowledgementsDirectory;
  private final Path draftsDirectory;
  private final String targetSession;

  HopLiveUiEventBroker(Path projectRoot) throws IOException {
    this(projectRoot, "");
  }

  HopLiveUiEventBroker(Path projectRoot, String targetSession) throws IOException {
    if (projectRoot == null) {
      throw new IllegalArgumentException("projectRoot is required");
    }
    Path normalized = projectRoot.toAbsolutePath().normalize();
    this.projectRoot = normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
    this.controlRoot = this.projectRoot.resolve(CONTROL_DIRECTORY);
    this.eventsDirectory = controlRoot.resolve("events");
    this.sessionsDirectory = controlRoot.resolve("sessions");
    this.acknowledgementsDirectory = controlRoot.resolve("acknowledgements");
    this.draftsDirectory = controlRoot.resolve("drafts");
    this.targetSession =
        targetSession.isEmpty() ? "" : requireUuid(targetSession, "target_session");
  }

  @Override
  public synchronized boolean publish(Event event) {
    if (event == null || !isAvailable()) {
      return false;
    }
    try {
      ensureDirectories();
      cleanupControlFiles(eventsDirectory, ".event", EVENT_TTL, MAX_EVENT_FILES - 1);
      cleanupControlFiles(
          acknowledgementsDirectory, ".ack", EVENT_TTL, MAX_ACKNOWLEDGEMENT_FILES - 1);
      String eventId = UUID.randomUUID().toString();
      Properties values = new Properties();
      values.setProperty("format", "1");
      values.setProperty("event_id", eventId);
      values.setProperty("created_at", Long.toString(System.currentTimeMillis()));
      values.setProperty("type", requireEventType(event.type()));
      values.setProperty("path", requireRelativeDefinitionPath(event.path()));
      values.setProperty("definition_kind", requireDefinitionKind(event.definitionKind()));
      values.setProperty("transaction_id", requireUuid(event.transactionId(), "transaction_id"));
      values.setProperty("old_sha256", requireSha256(event.oldSha256(), "old_sha256"));
      values.setProperty("new_sha256", requireSha256(event.newSha256(), "new_sha256"));
      values.setProperty("target_session", targetSession);
      writeProperties(eventsDirectory.resolve(eventId + ".event"), values);
      return true;
    } catch (IOException | IllegalArgumentException ignored) {
      return false;
    }
  }

  @Override
  public synchronized boolean publishPresentation(
      HopGuidedPresentation presentation, HopNativeDraft draft) {
    if (presentation == null || draft == null) return false;
    try {
      if (!hasPresentationClient()) return false;
      requireRelativeDefinitionPath(presentation.path());
      byte[] bytes = draft.encode();
      if (!ProjectFiles.sha256(bytes).equals(presentation.snapshotSha256())) return false;
      ensureDirectories();
      ensureSecureDirectory(draftsDirectory);
      pruneDrafts(bytes.length);
      Path target = draftPath(presentation);
      Path temporary = Files.createTempFile(draftsDirectory, ".draft-", ".tmp");
      try {
        Files.write(temporary, bytes);
        move(temporary, target);
      } finally {
        Files.deleteIfExists(temporary);
      }
      return publishPresentation(presentation);
    } catch (IOException | IllegalArgumentException ignored) {
      return false;
    }
  }

  synchronized HopNativeDraft readDraft(HopGuidedPresentation presentation) throws IOException {
    Path file = draftPath(presentation);
    if (!isSecureDirectory(controlRoot)
        || !isSecureDirectory(draftsDirectory)
        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(file)) throw new IOException("Native draft is unavailable");
    byte[] bytes;
    try (var input =
        Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      bytes = input.readNBytes(HopNativeDraft.MAX_BYTES + 1);
    }
    if (bytes.length > HopNativeDraft.MAX_BYTES
        || !ProjectFiles.sha256(bytes).equals(presentation.snapshotSha256()))
      throw new IOException("Native draft fingerprint changed");
    return HopNativeDraft.decode(bytes);
  }

  private Path draftPath(HopGuidedPresentation presentation) {
    return draftsDirectory.resolve(requireUuid(presentation.id(), "presentation_id") + ".draft");
  }

  private void pruneDrafts(int incomingBytes) throws IOException {
    List<Path> files;
    try (Stream<Path> paths = Files.list(draftsDirectory)) {
      files =
          paths
              .filter(
                  p ->
                      Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
                          && !Files.isSymbolicLink(p)
                          && p.getFileName().toString().endsWith(".draft"))
              .sorted(Comparator.comparingLong(this::lastModifiedSafely).reversed())
              .limit(MAX_PRESENTATION_FILES + 1)
              .toList();
    }
    long retained = incomingBytes;
    long oldest = System.currentTimeMillis() - HopCorrectionPlanManager.PLAN_TTL.toMillis();
    for (int i = 0; i < files.size(); i++) {
      Path file = files.get(i);
      long size = Files.size(file);
      if (i >= MAX_PRESENTATION_FILES - 1
          || lastModifiedSafely(file) < oldest
          || retained + size > MAX_DRAFT_STORAGE_BYTES) Files.deleteIfExists(file);
      else retained += size;
    }
  }

  @Override
  public synchronized boolean publishPresentation(HopGuidedPresentation presentation) {
    if (presentation == null) return false;
    try {
      if (!hasPresentationClient()) return false;
      requireRelativeDefinitionPath(presentation.path());
      ensureDirectories();
      cleanupControlFiles(eventsDirectory, ".visual", EVENT_TTL, MAX_PRESENTATION_FILES - 1);
      pruneRoutes();
      Path target =
          eventsDirectory.resolve(requireUuid(presentation.id(), "presentation_id") + ".visual");
      byte[] content = presentation.encode();
      if (!targetSession.isEmpty()) {
        Properties routing = new Properties();
        routing.setProperty("target_session", targetSession);
        writeProperties(eventsDirectory.resolve(presentation.id() + ".route"), routing);
      }
      Path temporary = Files.createTempFile(eventsDirectory, ".visual-", ".tmp");
      try {
        Files.write(temporary, content);
        move(temporary, target);
      } finally {
        Files.deleteIfExists(temporary);
      }
      return true;
    } catch (IOException | IllegalArgumentException ignored) {
      return false;
    }
  }

  private boolean hasPresentationClient() throws IOException {
    var clients = activeSessionsByClient();
    return clients.containsKey("desktop") || clients.containsKey("web");
  }

  synchronized List<HopGuidedPresentation> presentationsSince(long since) throws IOException {
    return presentationsSince(since, null);
  }

  synchronized List<HopGuidedPresentation> presentationsSince(long since, String receiver)
      throws IOException {
    if (!isSecureDirectory(eventsDirectory)) return List.of();
    List<HopGuidedPresentation> result = new ArrayList<>();
    try (Stream<Path> paths = Files.list(eventsDirectory)) {
      for (Path file :
          paths
              .filter(p -> isRegularControlFile(p, ".visual"))
              .sorted(Comparator.comparingLong(this::lastModifiedSafely).reversed())
              .limit(MAX_PRESENTATION_FILES)
              .toList()) {
        try {
          if (!presentationFor(file, receiver)) continue;
          HopGuidedPresentation presentation;
          try (var input =
              Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            presentation =
                HopGuidedPresentation.decode(input.readNBytes(HopGuidedPresentation.MAX_BYTES + 1));
          }
          requireRelativeDefinitionPath(presentation.path());
          if (!file.getFileName().toString().equals(presentation.id() + ".visual")) continue;
          if (presentation.updatedAt() >= since) result.add(presentation);
        } catch (IOException | IllegalArgumentException ignored) {
          /* Isolate malformed packets. */
        }
      }
    }
    result.sort(Comparator.comparingLong(HopGuidedPresentation::updatedAt));
    return List.copyOf(result);
  }

  private boolean presentationFor(Path file, String receiver) throws IOException {
    if (receiver == null) return true;
    String name = file.getFileName().toString();
    Path route =
        eventsDirectory.resolve(name.substring(0, name.length() - ".visual".length()) + ".route");
    if (!Files.exists(route, LinkOption.NOFOLLOW_LINKS)) return true;
    return receiver.equals(
        requireUuid(
            readProperties(route, ".route").getProperty("target_session"), "target_session"));
  }

  private void pruneRoutes() throws IOException {
    try (var files = Files.list(eventsDirectory)) {
      for (Path route :
          files
              .filter(p -> isRegularControlFile(p, ".route"))
              .limit(MAX_PRESENTATION_FILES + 1)
              .toList()) {
        String name = route.getFileName().toString();
        Path visual = eventsDirectory.resolve(name.substring(0, name.length() - 6) + ".visual");
        if (!Files.exists(visual, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(route);
      }
    }
  }

  @Override
  public synchronized boolean isAvailable() {
    try {
      return !activeSessionsByClient().isEmpty();
    } catch (IOException ignored) {
      return false;
    }
  }

  @Override
  public synchronized Map<String, Object> status(String transactionId) throws IOException {
    String normalizedTransactionId =
        transactionId == null || transactionId.isBlank()
            ? ""
            : requireUuid(transactionId, "transaction_id");
    List<String> matchingEventIds = new ArrayList<>();
    if (!normalizedTransactionId.isBlank()) {
      for (LiveEvent event : eventsSince(0L)) {
        if (normalizedTransactionId.equals(event.transactionId())) {
          matchingEventIds.add(event.eventId());
        }
      }
    }

    List<Map<String, Object>> acknowledgements =
        readAcknowledgements(matchingEventIds, normalizedTransactionId.isBlank());
    Map<String, Object> result = new LinkedHashMap<>();
    Map<String, Integer> activeClients = activeSessionsByClient();
    int activeSessions = activeClients.values().stream().mapToInt(Integer::intValue).sum();
    result.put("available", activeSessions > 0);
    result.put("adapter", "project_event_bridge");
    result.put("active_sessions", activeSessions);
    result.put("active_clients", activeClients);
    result.put("transaction_id", normalizedTransactionId);
    result.put("acknowledgements", acknowledgements);
    result.put("acknowledgement_count", acknowledgements.size());
    result.put("session_ttl_seconds", SESSION_TTL.toSeconds());
    return result;
  }

  synchronized LiveSession openSession(String clientType) throws IOException {
    String normalizedType = normalizeClientType(clientType);
    ensureDirectories();
    cleanupControlFiles(sessionsDirectory, ".session", SESSION_TTL, MAX_SESSION_FILES - 1);
    String sessionId = UUID.randomUUID().toString();
    Path marker = sessionsDirectory.resolve(sessionId + ".session");
    LiveSession session = new LiveSession(sessionId, normalizedType, marker);
    heartbeat(session);
    return session;
  }

  synchronized void heartbeat(LiveSession session) throws IOException {
    requireOwnedSession(session);
    Properties values = new Properties();
    values.setProperty("format", "1");
    values.setProperty("session_id", session.id());
    values.setProperty("client_type", session.clientType());
    values.setProperty("heartbeat_at", Long.toString(System.currentTimeMillis()));
    writeProperties(session.marker(), values);
  }

  synchronized List<LiveEvent> eventsSince(long createdAtInclusive) throws IOException {
    return eventsSince(createdAtInclusive, null);
  }

  synchronized List<LiveEvent> eventsSince(long createdAtInclusive, String receiver)
      throws IOException {
    if (!isSecureDirectory(eventsDirectory)) {
      return List.of();
    }
    List<Path> eventFiles;
    try (Stream<Path> paths = Files.list(eventsDirectory)) {
      eventFiles =
          paths
              .filter(path -> isRegularControlFile(path, ".event"))
              .sorted(Comparator.comparingLong(this::lastModifiedSafely).reversed())
              .limit(MAX_EVENT_FILES)
              .toList();
    }
    List<LiveEvent> events = new ArrayList<>();
    for (Path eventFile : eventFiles) {
      try {
        LiveEvent event = readEvent(eventFile);
        if (event.createdAt() >= createdAtInclusive
            && (receiver == null
                || event.targetSession().isEmpty()
                || receiver.equals(event.targetSession()))) {
          events.add(event);
        }
      } catch (IOException ignored) {
        // An invalid control file is isolated instead of blocking all valid events.
      }
    }
    events.sort(Comparator.comparingLong(LiveEvent::createdAt));
    return List.copyOf(events);
  }

  synchronized void acknowledge(LiveSession session, LiveEvent event, String status, String message)
      throws IOException {
    requireOwnedSession(session);
    if (event == null) {
      throw new IllegalArgumentException("event is required");
    }
    String normalizedStatus = normalizeAcknowledgementStatus(status);
    cleanupControlFiles(
        acknowledgementsDirectory, ".ack", EVENT_TTL, MAX_ACKNOWLEDGEMENT_FILES - 1);
    Properties values = new Properties();
    values.setProperty("format", "1");
    values.setProperty("event_id", event.eventId());
    values.setProperty("session_id", session.id());
    values.setProperty("client_type", session.clientType());
    values.setProperty("acknowledged_at", Long.toString(System.currentTimeMillis()));
    values.setProperty("status", normalizedStatus);
    values.setProperty("message", sanitizeMessage(message));
    writeProperties(
        acknowledgementsDirectory.resolve(event.eventId() + "-" + session.id() + ".ack"), values);
  }

  private Map<String, Integer> activeSessionsByClient() throws IOException {
    if (!isSecureDirectory(sessionsDirectory)) {
      return Map.of();
    }
    cleanupControlFiles(sessionsDirectory, ".session", SESSION_TTL, MAX_SESSION_FILES);
    long newestAllowed = System.currentTimeMillis() - SESSION_TTL.toMillis();
    Map<String, Integer> active = new LinkedHashMap<>();
    try (Stream<Path> paths = Files.list(sessionsDirectory)) {
      for (Path path :
          paths
              .filter(candidate -> isRegularControlFile(candidate, ".session"))
              .limit(MAX_SESSION_FILES)
              .toList()) {
        if (Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()
            >= newestAllowed) {
          try {
            Properties values = readProperties(path, ".session");
            String clientType = normalizeClientType(values.getProperty("client_type"));
            requireUuid(values.getProperty("session_id"), "session_id");
            active.merge(clientType, 1, Integer::sum);
          } catch (RuntimeException | IOException ignored) {
            // Invalid session markers never make the bridge available.
          }
        } else {
          Files.deleteIfExists(path);
        }
      }
    }
    return Map.copyOf(active);
  }

  private List<Map<String, Object>> readAcknowledgements(
      List<String> matchingEventIds, boolean includeAll) throws IOException {
    if (!isSecureDirectory(acknowledgementsDirectory)) {
      return List.of();
    }
    List<Path> files;
    try (Stream<Path> paths = Files.list(acknowledgementsDirectory)) {
      files =
          paths
              .filter(path -> isRegularControlFile(path, ".ack"))
              .sorted(Comparator.comparingLong(this::lastModifiedSafely).reversed())
              .limit(100)
              .toList();
    }
    List<Map<String, Object>> acknowledgements = new ArrayList<>();
    for (Path file : files) {
      try {
        Properties values = readProperties(file, ".ack");
        String eventId = requireUuid(values.getProperty("event_id"), "event_id");
        if (!includeAll && !matchingEventIds.contains(eventId)) {
          continue;
        }
        Map<String, Object> acknowledgement = new LinkedHashMap<>();
        acknowledgement.put("event_id", eventId);
        acknowledgement.put(
            "acknowledged_at", Long.parseLong(values.getProperty("acknowledged_at")));
        acknowledgement.put("status", normalizeAcknowledgementStatus(values.getProperty("status")));
        acknowledgement.put("client_type", normalizeClientType(values.getProperty("client_type")));
        acknowledgement.put("message", sanitizeMessage(values.getProperty("message")));
        acknowledgements.add(acknowledgement);
      } catch (RuntimeException | IOException ignored) {
        // Invalid acknowledgements are isolated from valid delivery state.
      }
    }
    return List.copyOf(acknowledgements);
  }

  private void ensureDirectories() throws IOException {
    ensureSecureDirectory(controlRoot);
    ensureSecureDirectory(eventsDirectory);
    ensureSecureDirectory(sessionsDirectory);
    ensureSecureDirectory(acknowledgementsDirectory);
  }

  private void ensureSecureDirectory(Path directory) throws IOException {
    if (!directory.normalize().startsWith(projectRoot)) {
      throw new IOException("Live UI control directory escaped the project root");
    }
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
      Files.createDirectory(directory);
    }
    if (!isSecureDirectory(directory)) {
      throw new IOException("Live UI control path is not a secure directory: " + directory);
    }
  }

  private boolean isSecureDirectory(Path directory) throws IOException {
    return Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
        && !Files.isSymbolicLink(directory)
        && directory.toRealPath(LinkOption.NOFOLLOW_LINKS).startsWith(projectRoot);
  }

  private boolean isRegularControlFile(Path path, String suffix) {
    try {
      return path.getParent().normalize().startsWith(controlRoot)
          && path.getFileName().toString().endsWith(suffix)
          && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
          && !Files.isSymbolicLink(path)
          && Files.size(path)
              <= (".visual".equals(suffix)
                  ? HopGuidedPresentation.MAX_BYTES
                  : MAX_CONTROL_FILE_BYTES);
    } catch (IOException ignored) {
      return false;
    }
  }

  private void writeProperties(Path target, Properties values) throws IOException {
    if (!target.getParent().normalize().startsWith(controlRoot)) {
      throw new IOException("Live UI control file escaped the project root");
    }
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    values.store(output, "MCP Connector for Apache Hop live UI event");
    byte[] content = output.toByteArray();
    if (content.length > MAX_CONTROL_FILE_BYTES) {
      throw new IOException("Live UI control file exceeds size limit");
    }
    Path temporary = Files.createTempFile(target.getParent(), "." + target.getFileName(), ".tmp");
    try {
      Files.write(temporary, content, StandardOpenOption.TRUNCATE_EXISTING);
      move(temporary, target);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private LiveEvent readEvent(Path eventFile) throws IOException {
    Properties values = readProperties(eventFile, ".event");
    if (!"1".equals(values.getProperty("format"))) {
      throw new IOException("Unsupported live UI event format");
    }
    try {
      return new LiveEvent(
          requireUuid(values.getProperty("event_id"), "event_id"),
          Long.parseLong(values.getProperty("created_at")),
          requireEventType(values.getProperty("type")),
          requireRelativeDefinitionPath(values.getProperty("path")),
          requireDefinitionKind(values.getProperty("definition_kind")),
          requireUuid(values.getProperty("transaction_id"), "transaction_id"),
          requireSha256(values.getProperty("old_sha256"), "old_sha256"),
          requireSha256(values.getProperty("new_sha256"), "new_sha256"),
          values.getProperty("target_session", "").isEmpty()
              ? ""
              : requireUuid(values.getProperty("target_session"), "target_session"));
    } catch (RuntimeException e) {
      throw new IOException("Invalid live UI event", e);
    }
  }

  private Properties readProperties(Path file, String suffix) throws IOException {
    if (!isRegularControlFile(file, suffix)) {
      throw new IOException("Invalid live UI control file");
    }
    byte[] content = Files.readAllBytes(file);
    Properties values = new Properties();
    values.load(new ByteArrayInputStream(content));
    return values;
  }

  private void cleanupControlFiles(Path directory, String suffix, Duration ttl, int maximumFiles)
      throws IOException {
    if (!isSecureDirectory(directory)) {
      return;
    }
    long oldestAllowed = System.currentTimeMillis() - ttl.toMillis();
    List<Path> controlFiles;
    try (Stream<Path> paths = Files.list(directory)) {
      controlFiles =
          paths
              .filter(path -> isRegularControlFile(path, suffix))
              .sorted(Comparator.comparingLong(this::lastModifiedSafely).reversed())
              .toList();
    }
    for (int index = 0; index < controlFiles.size(); index++) {
      Path path = controlFiles.get(index);
      if (index >= maximumFiles || lastModifiedSafely(path) < oldestAllowed) {
        Files.deleteIfExists(path);
      }
    }
  }

  private long lastModifiedSafely(Path path) {
    try {
      return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
    } catch (IOException ignored) {
      return 0L;
    }
  }

  private void requireOwnedSession(LiveSession session) {
    if (session == null
        || session.closed()
        || !session.marker().getParent().equals(sessionsDirectory)
        || !UUID_PATTERN.matcher(session.id()).matches()) {
      throw new IllegalArgumentException("Unknown or closed live UI session");
    }
  }

  private static String normalizeClientType(String clientType) {
    String normalized = clientType == null ? "" : clientType.toLowerCase(Locale.ROOT);
    if (!CLIENT_TYPE.matcher(normalized).matches()) {
      throw new IllegalArgumentException("clientType must match " + CLIENT_TYPE.pattern());
    }
    return normalized;
  }

  private static String requireEventType(String value) {
    return switch (value == null ? "" : value) {
      case "mutation_applied", "mutation_rolled_back" -> value;
      default -> throw new IllegalArgumentException("Unsupported live UI event type");
    };
  }

  private static String requireDefinitionKind(String value) {
    return switch (value == null ? "" : value) {
      case "pipeline", "workflow" -> value;
      default -> throw new IllegalArgumentException("Unsupported definition kind");
    };
  }

  private static String requireRelativeDefinitionPath(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("path is required");
    }
    Path path = Path.of(value.replace('/', java.io.File.separatorChar)).normalize();
    if (path.isAbsolute()
        || path.startsWith("..")
        || path.getNameCount() == 0
        || CONTROL_DIRECTORY.equals(path.getName(0).toString())
        || !(value.toLowerCase(Locale.ROOT).endsWith(".hpl")
            || value.toLowerCase(Locale.ROOT).endsWith(".hwf"))) {
      throw new IllegalArgumentException("path must be a project-relative Hop definition");
    }
    return path.toString().replace('\\', '/');
  }

  private static String requireUuid(String value, String field) {
    if (value == null || !UUID_PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be a UUID");
    }
    return value.toLowerCase(Locale.ROOT);
  }

  private static String requireSha256(String value, String field) {
    if (value == null || !SHA_256.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be a SHA-256 fingerprint");
    }
    return value.toLowerCase(Locale.ROOT);
  }

  private static String sanitizeMessage(String message) {
    if (message == null) {
      return "";
    }
    String sanitized = message.replace('\r', ' ').replace('\n', ' ');
    return sanitized.substring(0, Math.min(sanitized.length(), 500));
  }

  private static String normalizeAcknowledgementStatus(String status) {
    String normalized = status == null ? "" : status.toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "reloaded", "opened", "closed", "not_open", "skipped_dirty", "error" -> normalized;
      default -> throw new IllegalArgumentException("Unsupported acknowledgement status");
    };
  }

  private static void move(Path source, Path target) throws IOException {
    try {
      Files.move(
          source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  record LiveEvent(
      String eventId,
      long createdAt,
      String type,
      String path,
      String definitionKind,
      String transactionId,
      String oldSha256,
      String newSha256,
      String targetSession) {
    LiveEvent(
        String eventId,
        long createdAt,
        String type,
        String path,
        String definitionKind,
        String transactionId,
        String oldSha256,
        String newSha256) {
      this(eventId, createdAt, type, path, definitionKind, transactionId, oldSha256, newSha256, "");
    }
  }

  final class LiveSession implements AutoCloseable {
    private final String id;
    private final String clientType;
    private final Path marker;
    private boolean closed;

    private LiveSession(String id, String clientType, Path marker) {
      this.id = id;
      this.clientType = clientType;
      this.marker = marker;
    }

    String id() {
      return id;
    }

    String clientType() {
      return clientType;
    }

    Path marker() {
      return marker;
    }

    boolean closed() {
      return closed;
    }

    @Override
    public synchronized void close() throws IOException {
      if (!closed) {
        closed = true;
        Files.deleteIfExists(marker);
      }
    }
  }
}
