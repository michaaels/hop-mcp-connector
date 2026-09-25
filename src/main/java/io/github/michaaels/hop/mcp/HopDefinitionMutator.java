package io.github.michaaels.hop.mcp;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.pipeline.PipelineHopMeta;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.apache.hop.workflow.WorkflowHopMeta;
import org.apache.hop.workflow.WorkflowMeta;
import org.apache.hop.workflow.action.ActionMeta;

/**
 * Applies a deliberately small set of mutations through Hop semantic objects. The caller never
 * supplies XML; Hop owns parsing and serialization.
 */
final class HopDefinitionMutator {
  static final int MAX_OPERATIONS = 50;
  static final int MAX_TRANSACTIONS = 100;
  static final long MAX_BACKUP_BYTES = 32L * 1024 * 1024;
  static final Duration TRANSACTION_TTL = Duration.ofHours(1);
  private static final int MAX_BACKUP_SCAN_ENTRIES = 1_000;
  private static final String BACKUP_FILE_NAME = "definition.backup";

  private final ProjectFiles files;
  private final IVariables variables;
  private final IHopMetadataProvider metadataProvider;
  private final HopSemanticEventSink eventSink;
  private final HopComponentAuthoring componentAuthoring;
  private final Consumer<Path> definitionInvalidator;
  private final Map<String, MutationRecord> transactions = new LinkedHashMap<>();

  HopDefinitionMutator(
      ProjectFiles files, IVariables variables, IHopMetadataProvider metadataProvider) {
    this(files, variables, metadataProvider, HopSemanticEventSink.NONE);
  }

  HopDefinitionMutator(
      ProjectFiles files,
      IVariables variables,
      IHopMetadataProvider metadataProvider,
      HopSemanticEventSink eventSink) {
    this(files, variables, metadataProvider, eventSink, ignored -> {});
  }

  HopDefinitionMutator(
      ProjectFiles files,
      IVariables variables,
      IHopMetadataProvider metadataProvider,
      HopSemanticEventSink eventSink,
      Consumer<Path> definitionInvalidator) {
    this.files = files;
    this.variables = variables;
    this.metadataProvider = metadataProvider;
    this.eventSink = eventSink == null ? HopSemanticEventSink.NONE : eventSink;
    this.componentAuthoring = new HopComponentAuthoring(metadataProvider);
    this.definitionInvalidator =
        definitionInvalidator == null ? ignored -> {} : definitionInvalidator;
  }

  synchronized Map<String, Object> mutate(
      String relative,
      String requestedKind,
      List<Map<String, Object>> operations,
      String expectedSha256,
      boolean apply)
      throws Exception {
    requireRuntime();
    String kind = kind(relative, requestedKind);
    List<Map<String, Object>> requestedOperations =
        operations == null ? List.of() : List.copyOf(operations);
    if (requestedOperations.size() > MAX_OPERATIONS) {
      throw new IllegalArgumentException("operations cannot exceed " + MAX_OPERATIONS + " entries");
    }

    Path target = files.resolveForWrite(relative);
    boolean existed = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
    byte[] previous = existed ? files.readBytes(target) : new byte[0];
    String oldHash = ProjectFiles.sha256(previous);
    verifyPrecondition(existed, oldHash, expectedSha256, apply);

    Definition definition =
        existed ? load(kind, previous, target.toString()) : create(kind, target, relative);
    Map<String, Object> before = definition.summary();
    List<Map<String, Object>> changes = new ArrayList<>();
    for (Map<String, Object> operation : requestedOperations) {
      changes.add(definition.apply(operation));
    }

    byte[] serialized = definition.xml().getBytes(StandardCharsets.UTF_8);
    if (serialized.length > ProjectFiles.MAX_READ_BYTES) {
      throw new IOException("Serialized definition exceeds write limit: " + serialized.length);
    }
    validateSerialized(kind, serialized, target.toString());
    String newHash = ProjectFiles.sha256(serialized);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("path", relative.replace('\\', '/'));
    result.put("kind", kind);
    result.put("target_exists", existed);
    result.put("preview", !apply);
    result.put("applied", false);
    result.put("changed", !oldHash.equals(newHash));
    result.put("old_sha256", oldHash);
    result.put("new_sha256", newHash);
    result.put("before", before);
    result.put("after", definition.summary());
    result.put("changes", changes);
    result.put("native_reload_valid", true);
    result.put("backup", "");
    result.put("transaction_id", "");
    result.put("rollback_available", false);

    if (!apply || oldHash.equals(newHash)) {
      return result;
    }

    Instant createdAt = Instant.now();
    expireTransactions(createdAt);
    if (transactions.size() >= MAX_TRANSACTIONS) {
      throw new IOException(
          "Mutation transaction limit reached; retry after a transaction expires");
    }
    BackupUsage backupUsage = inspectBackupStore(createdAt);
    if (existed && backupUsage.bytes > MAX_BACKUP_BYTES - previous.length) {
      throw new IOException("Mutation backup storage byte limit reached");
    }
    if (existed && backupUsage.directories >= MAX_TRANSACTIONS) {
      throw new IOException("Mutation backup storage transaction limit reached");
    }

    String transactionId = UUID.randomUUID().toString();
    Path backup = null;
    if (existed) {
      backup = createBackup(transactionId, previous);
    }

    try {
      boolean atomicReplaceUsed = atomicReplace(target, serialized);
      validateFile(kind, target);
      definitionInvalidator.accept(target);
      result.put("atomic_replace_used", atomicReplaceUsed);
    } catch (Exception writeFailure) {
      try {
        rollbackFailedWrite(target, backup, existed, transactionId, oldHash);
        if (backup != null) deleteBackupDirectory(transactionId);
      } catch (Exception recoveryFailure) {
        writeFailure.addSuppressed(recoveryFailure);
      }
      throw new IOException(
          "Native validation failed after write; recovery was attempted", writeFailure);
    }

    retainTransaction(
        new MutationRecord(
            transactionId,
            relative,
            kind,
            target,
            backup,
            existed,
            oldHash,
            newHash,
            createdAt.plus(TRANSACTION_TTL)));
    result.put("applied", true);
    result.put("preview", false);
    result.put("backup", backup == null ? "" : "protected");
    result.put("transaction_id", transactionId);
    result.put("rollback_available", true);
    result.put("expires_at", createdAt.plus(TRANSACTION_TTL).toString());
    result.put(
        "semantic_event_published",
        publishEvent(
            new HopSemanticEventSink.Event(
                "mutation_applied",
                relative.replace('\\', '/'),
                kind,
                transactionId,
                oldHash,
                newHash,
                changes)));
    return result;
  }

  synchronized Map<String, Object> rollback(String transactionId, String expectedSha256)
      throws Exception {
    expireTransactions(Instant.now());
    if (transactionId == null || transactionId.isBlank()) {
      throw new IllegalArgumentException("transaction_id is required");
    }
    MutationRecord record = transactions.get(transactionId);
    if (record == null || record.rolledBack) {
      throw new IllegalArgumentException("Unknown or already rolled back mutation transaction");
    }
    if (!Files.isRegularFile(record.target, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(record.target)) {
      throw new IOException("Mutated definition is missing or no longer a regular file");
    }
    byte[] current = files.readBytes(record.target);
    String currentHash = ProjectFiles.sha256(current);
    if (expectedSha256 == null
        || expectedSha256.isBlank()
        || !expectedSha256.equalsIgnoreCase(currentHash)) {
      throw McpException.precondition(
          "ROLLBACK_STALE_SHA256",
          "Definition changed after the mutation; refresh its SHA-256 before rollback.",
          true);
    }

    boolean atomicReplaceUsed = false;
    if (record.existed) {
      if (record.backup == null || !isSafeBackupFile(record.id, record.backup)) {
        throw new IOException("Mutation backup is unavailable");
      }
      byte[] original = files.readBytes(record.backup);
      if (!record.oldHash.equals(ProjectFiles.sha256(original))) {
        throw new IOException("Mutation backup integrity check failed");
      }
      atomicReplaceUsed = atomicReplace(record.target, original);
      try {
        validateFile(record.kind, record.target);
        definitionInvalidator.accept(record.target);
      } catch (Exception rollbackFailure) {
        try {
          atomicReplace(record.target, current);
          validateFile(record.kind, record.target);
          definitionInvalidator.accept(record.target);
        } catch (Exception recoveryFailure) {
          rollbackFailure.addSuppressed(recoveryFailure);
        }
        throw rollbackFailure;
      }
    } else {
      Files.delete(record.target);
      definitionInvalidator.accept(record.target);
    }

    record.rolledBack = true;
    if (record.backup != null) {
      try {
        deleteBackupDirectory(record.id);
      } catch (IOException ignored) {
        // A completed rollback must remain successful if only backup cleanup failed.
      }
    }
    transactions.remove(transactionId);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("transaction_id", transactionId);
    result.put("path", record.relative.replace('\\', '/'));
    result.put("rolled_back", true);
    result.put("restored_existing_file", record.existed);
    result.put("restored_sha256", record.oldHash);
    result.put("atomic_replace_used", atomicReplaceUsed);
    result.put(
        "semantic_event_published",
        publishEvent(
            new HopSemanticEventSink.Event(
                "mutation_rolled_back",
                record.relative.replace('\\', '/'),
                record.kind,
                transactionId,
                currentHash,
                record.oldHash,
                List.of())));
    return result;
  }

  private boolean publishEvent(HopSemanticEventSink.Event event) {
    try {
      return eventSink.publish(event);
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private void requireRuntime() {
    if (variables == null || metadataProvider == null) {
      throw new IllegalStateException(
          "Native mutation requires an initialized variables space and metadata provider");
    }
  }

  private static void verifyPrecondition(
      boolean existed, String oldHash, String expectedSha256, boolean apply) throws IOException {
    if (!existed && expectedSha256 != null && !expectedSha256.isBlank()) {
      throw new IllegalArgumentException(
          "expected_sha256 must be empty when creating a definition");
    }
    if (existed
        && expectedSha256 != null
        && !expectedSha256.isBlank()
        && !expectedSha256.equalsIgnoreCase(oldHash)) {
      throw McpException.precondition(
          "STALE_SHA256",
          "Definition changed since it was read; refresh its SHA-256 and retry.",
          true);
    }
    if (apply && existed && (expectedSha256 == null || expectedSha256.isBlank())) {
      throw new IllegalArgumentException(
          "expected_sha256 is required when mutating an existing definition");
    }
  }

  private Definition create(String kind, Path target, String relative) {
    String defaultName = target.getFileName().toString();
    int dot = defaultName.lastIndexOf('.');
    if (dot > 0) defaultName = defaultName.substring(0, dot);
    if ("pipeline".equals(kind)) {
      PipelineMeta meta = new PipelineMeta();
      meta.setFilename(target.toString());
      meta.setNameSynchronizedWithFilename(false);
      meta.setName(defaultName);
      return new PipelineDefinition(meta);
    }
    WorkflowMeta meta = new WorkflowMeta();
    meta.setFilename(target.toString());
    meta.setNameSynchronizedWithFilename(false);
    meta.setName(defaultName);
    return new WorkflowDefinition(meta);
  }

  private Definition load(String kind, byte[] content, String filename) throws Exception {
    String xml = new String(content, StandardCharsets.UTF_8);
    HopXml.parse(xml);
    if ("pipeline".equals(kind)) {
      return new PipelineDefinition(
          new PipelineMeta(new ByteArrayInputStream(content), metadataProvider, variables));
    }
    return new WorkflowDefinition(
        new WorkflowMeta(new ByteArrayInputStream(content), metadataProvider, variables));
  }

  private void validateSerialized(String kind, byte[] content, String filename) throws Exception {
    load(kind, content, filename);
  }

  private void validateFile(String kind, Path target) throws Exception {
    byte[] content = files.readBytes(target);
    validateSerialized(kind, content, target.toString());
  }

  private static String kind(String relative, String requestedKind) {
    if (relative == null || relative.isBlank()) {
      throw new IllegalArgumentException("path is required");
    }
    String lower = relative.toLowerCase(Locale.ROOT);
    String inferred =
        lower.endsWith(".hpl") ? "pipeline" : lower.endsWith(".hwf") ? "workflow" : null;
    if (inferred == null) {
      throw new IllegalArgumentException("Native mutation supports .hpl and .hwf definitions only");
    }
    if (requestedKind != null
        && !requestedKind.isBlank()
        && !inferred.equals(requestedKind.toLowerCase(Locale.ROOT))) {
      throw new IllegalArgumentException("kind does not match the definition file extension");
    }
    return inferred;
  }

  private static boolean atomicReplace(Path target, byte[] content) throws IOException {
    Path temporary =
        Files.createTempFile(target.getParent(), "." + target.getFileName() + ".mcp-", ".tmp");
    try {
      Files.write(temporary, content, StandardOpenOption.TRUNCATE_EXISTING);
      boolean atomicMoveUsed = move(temporary, target);
      return atomicMoveUsed;
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static boolean move(Path source, Path target) throws IOException {
    try {
      Files.move(
          source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      return true;
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
      return false;
    }
  }

  private void rollbackFailedWrite(
      Path target, Path backup, boolean existed, String transactionId, String expectedHash)
      throws IOException {
    try {
      if (existed && backup != null && isSafeBackupFile(transactionId, backup)) {
        byte[] original = files.readBytes(backup);
        if (!expectedHash.equals(ProjectFiles.sha256(original))) {
          throw new IOException("Mutation backup integrity check failed during recovery");
        }
        atomicReplace(target, original);
      } else if (!existed) {
        Files.deleteIfExists(target);
      } else {
        throw new IOException("Mutation backup is unavailable during recovery");
      }
    } finally {
      definitionInvalidator.accept(target);
    }
  }

  private void retainTransaction(MutationRecord record) {
    transactions.put(record.id, record);
  }

  private void expireTransactions(Instant now) {
    var iterator = transactions.entrySet().iterator();
    while (iterator.hasNext()) {
      MutationRecord record = iterator.next().getValue();
      if (!now.isBefore(record.expiresAt)) {
        if (record.backup != null) {
          try {
            deleteBackupDirectory(record.id);
          } catch (IOException ignored) {
            // Expired transactions are no longer rollback-capable; leave unsafe state untouched.
          }
        }
        iterator.remove();
      }
    }
  }

  private Path createBackup(String transactionId, byte[] previous) throws IOException {
    Path directory = ensureBackupRoot().resolve(transactionId);
    try {
      Files.createDirectory(directory);
    } catch (FileAlreadyExistsException e) {
      throw new IOException("Mutation backup transaction directory already exists", e);
    }
    Path backup = directory.resolve(BACKUP_FILE_NAME);
    try {
      Files.write(backup, previous, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
      return backup;
    } catch (IOException failure) {
      try {
        deleteBackupDirectory(transactionId);
      } catch (IOException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  private BackupUsage inspectBackupStore(Instant now) throws IOException {
    Path root = backupRootIfPresent();
    if (root == null) return new BackupUsage(0, 0);
    int directories = 0;
    long bytes = 0;
    int scanned = 0;
    List<String> expiredDirectories = new ArrayList<>();
    try (Stream<Path> entries = Files.list(root)) {
      for (Path directory : (Iterable<Path>) entries::iterator) {
        if (++scanned > MAX_BACKUP_SCAN_ENTRIES) {
          throw new IOException("Mutation backup storage scan limit reached");
        }
        String id = directory.getFileName().toString();
        if (!isTransactionId(id)
            || Files.isSymbolicLink(directory)
            || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
          throw new IOException("Mutation backup storage contains malformed state");
        }
        Path backup = directory.resolve(BACKUP_FILE_NAME);
        int childCount = 0;
        try (Stream<Path> children = Files.list(directory)) {
          for (Path child : (Iterable<Path>) children::iterator) {
            if (++childCount > 1
                || !child.getFileName().toString().equals(BACKUP_FILE_NAME)
                || Files.isSymbolicLink(child)
                || !Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
              throw new IOException("Mutation backup storage contains malformed state");
            }
          }
        }
        if (childCount == 1) {
          long size = Files.size(backup);
          if (size > ProjectFiles.MAX_READ_BYTES) {
            throw new IOException("Mutation backup storage contains an oversized backup");
          }
          bytes = Math.addExact(bytes, size);
        }

        MutationRecord record = transactions.get(id);
        if (record != null && !record.rolledBack && (record.backup == null || childCount != 1)) {
          throw new IOException("Mutation backup storage contains malformed state");
        }
        boolean active = record != null && !record.rolledBack && now.isBefore(record.expiresAt);
        boolean rolledBack = record != null && record.rolledBack;
        boolean expired =
            !now.isBefore(
                Files.getLastModifiedTime(directory, LinkOption.NOFOLLOW_LINKS)
                    .toInstant()
                    .plus(TRANSACTION_TTL));
        if ((rolledBack || (expired && !active))) {
          expiredDirectories.add(id);
          if (childCount == 1) bytes -= Files.size(backup);
          continue;
        }
        directories++;
      }
    } catch (ArithmeticException e) {
      throw new IOException("Mutation backup storage byte count overflowed", e);
    }
    for (String id : expiredDirectories) deleteBackupDirectory(id);
    if (directories > MAX_TRANSACTIONS || bytes > MAX_BACKUP_BYTES) {
      throw new IOException("Mutation backup storage limit exceeded");
    }
    return new BackupUsage(directories, bytes);
  }

  private Path ensureBackupRoot() throws IOException {
    Path control = files.root().resolve(HopLiveUiEventBroker.CONTROL_DIRECTORY);
    ensureDirectory(control);
    Path backups = control.resolve("backups");
    ensureDirectory(backups);
    return backups;
  }

  private Path backupRootIfPresent() throws IOException {
    Path control = files.root().resolve(HopLiveUiEventBroker.CONTROL_DIRECTORY);
    if (!Files.exists(control, LinkOption.NOFOLLOW_LINKS)) return null;
    requireSafeDirectory(control);
    Path backups = control.resolve("backups");
    if (!Files.exists(backups, LinkOption.NOFOLLOW_LINKS)) return null;
    requireSafeDirectory(backups);
    return backups;
  }

  private static void ensureDirectory(Path directory) throws IOException {
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
      try {
        Files.createDirectory(directory);
      } catch (FileAlreadyExistsException ignored) {
        // Validate the concurrently created entry below.
      }
    }
    requireSafeDirectory(directory);
  }

  private static void requireSafeDirectory(Path directory) throws IOException {
    if (Files.isSymbolicLink(directory)
        || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Mutation backup storage path is not a safe directory");
    }
  }

  private boolean isSafeBackupFile(String transactionId, Path backup) throws IOException {
    if (!isTransactionId(transactionId)) return false;
    Path root = backupRootIfPresent();
    if (root == null) return false;
    Path directory = root.resolve(transactionId);
    return !Files.isSymbolicLink(directory)
        && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
        && backup.equals(directory.resolve(BACKUP_FILE_NAME))
        && !Files.isSymbolicLink(backup)
        && Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS);
  }

  private void deleteBackupDirectory(String transactionId) throws IOException {
    if (!isTransactionId(transactionId)) throw new IOException("Invalid mutation transaction id");
    Path root = backupRootIfPresent();
    if (root == null) return;
    Path directory = root.resolve(transactionId);
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
    if (Files.isSymbolicLink(directory)
        || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Mutation backup transaction directory is unsafe");
    }
    List<Path> childrenToDelete = new ArrayList<>();
    try (Stream<Path> children = Files.list(directory)) {
      for (Path child : (Iterable<Path>) children::iterator) {
        if (!child.getFileName().toString().equals(BACKUP_FILE_NAME)
            || Files.isSymbolicLink(child)
            || !Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
          throw new IOException("Mutation backup transaction directory is malformed");
        }
        childrenToDelete.add(child);
      }
    }
    for (Path child : childrenToDelete) Files.delete(child);
    Files.deleteIfExists(directory);
  }

  private static boolean isTransactionId(String value) {
    try {
      return UUID.fromString(value).toString().equals(value);
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static final class BackupUsage {
    private final int directories;
    private final long bytes;

    private BackupUsage(int directories, long bytes) {
      this.directories = directories;
      this.bytes = bytes;
    }
  }

  private interface Definition {
    Map<String, Object> summary();

    Map<String, Object> apply(Map<String, Object> operation) throws Exception;

    String xml() throws Exception;
  }

  private final class PipelineDefinition implements Definition {
    private final PipelineMeta meta;

    private PipelineDefinition(PipelineMeta meta) {
      this.meta = meta;
    }

    @Override
    public Map<String, Object> summary() {
      return Map.of(
          "name", value(meta.getName()),
          "description_present", meta.getDescription() != null && !meta.getDescription().isBlank(),
          "component_count", meta.nrTransforms(),
          "hop_count", meta.nrPipelineHops());
    }

    @Override
    public Map<String, Object> apply(Map<String, Object> operation) throws Exception {
      String name = operationName(operation);
      return switch (name) {
        case "add_component" -> {
          String componentName = required(operation, "name");
          if (meta.findTransform(componentName, null) != null) {
            throw McpException.conflict(
                "SEMANTIC_ENTITY_EXISTS", "Transform name already exists: " + componentName);
          }
          int x = optionalCoordinate(operation, "x", 50);
          int y = optionalCoordinate(operation, "y", 50);
          TransformMeta transform =
              componentAuthoring.createTransform(
                  required(operation, "plugin_id"),
                  componentName,
                  operation.get("properties"),
                  operation.get("property_groups"),
                  x,
                  y);
          meta.addTransform(transform);
          yield Map.of(
              "operation", name,
              "component", componentName,
              "plugin_id", transform.getPluginId(),
              "x", x,
              "y", y,
              "property_count", propertyCount(operation.get("properties")),
              "property_group_count", propertyCount(operation.get("property_groups")));
        }
        case "update_component" -> {
          String componentName = required(operation, "component");
          requireComponentUpdates(operation);
          TransformMeta transform = requireTransform(meta, componentName);
          componentAuthoring.updateComponent(
              transform.getTransform(),
              operation.get("properties"),
              operation.get("property_groups"));
          yield Map.of(
              "operation", name,
              "component", componentName,
              "plugin_id", transform.getPluginId(),
              "property_count", propertyCount(operation.get("properties")),
              "property_group_count", propertyCount(operation.get("property_groups")));
        }
        case "set_name" -> {
          String next = required(operation, "value");
          String previous = value(meta.getName());
          meta.setNameSynchronizedWithFilename(false);
          meta.setName(next);
          yield change(name, "from", previous, "to", next);
        }
        case "set_description" -> {
          meta.setDescription(stringValue(operation, "value", 8192));
          yield Map.of("operation", name, "description_changed", true);
        }
        case "rename_component" -> {
          String component = required(operation, "component");
          String next = required(operation, "new_name");
          TransformMeta transform = meta.findTransform(component, null);
          if (transform == null)
            throw new IllegalArgumentException("Unknown transform: " + component);
          if (meta.findTransform(next, transform) != null) {
            throw McpException.conflict(
                "SEMANTIC_ENTITY_EXISTS", "Transform name already exists: " + next);
          }
          transform.setName(next);
          yield change(name, "from", component, "to", next);
        }
        case "move_component" -> {
          String component = required(operation, "component");
          TransformMeta transform = requireTransform(meta, component);
          int x = coordinate(operation, "x");
          int y = coordinate(operation, "y");
          transform.setLocation(x, y);
          yield Map.of("operation", name, "component", component, "x", x, "y", y);
        }
        case "add_hop" -> {
          String from = required(operation, "from");
          String to = required(operation, "to");
          TransformMeta fromMeta = requireTransform(meta, from);
          TransformMeta toMeta = requireTransform(meta, to);
          if (fromMeta == toMeta) throw new IllegalArgumentException("A hop cannot target itself");
          if (meta.findPipelineHop(fromMeta, toMeta, true) != null) {
            throw McpException.conflict(
                "SEMANTIC_ENTITY_EXISTS", "Hop already exists: " + from + " -> " + to);
          }
          boolean enabled = optionalBoolean(operation, "enabled", true);
          meta.addPipelineHop(
              meta.nrPipelineHops(), new PipelineHopMeta(fromMeta, toMeta, enabled));
          yield Map.of("operation", name, "from", from, "to", to, "enabled", enabled);
        }
        case "remove_hop" -> {
          String from = required(operation, "from");
          String to = required(operation, "to");
          PipelineHopMeta hop =
              meta.findPipelineHop(requireTransform(meta, from), requireTransform(meta, to), true);
          if (hop == null) throw new IllegalArgumentException("Unknown hop: " + from + " -> " + to);
          meta.removePipelineHop(hop);
          yield Map.of("operation", name, "from", from, "to", to);
        }
        case "set_hop_enabled" -> {
          String from = required(operation, "from");
          String to = required(operation, "to");
          TransformMeta fromMeta = meta.findTransform(from, null);
          TransformMeta toMeta = meta.findTransform(to, null);
          if (fromMeta == null || toMeta == null) {
            throw new IllegalArgumentException("Unknown transform in hop: " + from + " -> " + to);
          }
          PipelineHopMeta hop = meta.findPipelineHop(fromMeta, toMeta, true);
          if (hop == null) throw new IllegalArgumentException("Unknown hop: " + from + " -> " + to);
          boolean enabled = booleanValue(operation, "enabled");
          hop.setEnabled(enabled);
          yield Map.of("operation", name, "from", from, "to", to, "enabled", enabled);
        }
        case "remove_component" -> {
          String component = required(operation, "component");
          TransformMeta transform = requireTransform(meta, component);
          meta.removeTransform(meta.indexOfTransform(transform));
          yield Map.of("operation", name, "component", component);
        }
        default -> throw new IllegalArgumentException("Unsupported pipeline mutation: " + name);
      };
    }

    @Override
    public String xml() throws Exception {
      return meta.getXml(variables);
    }
  }

  private final class WorkflowDefinition implements Definition {
    private final WorkflowMeta meta;

    private WorkflowDefinition(WorkflowMeta meta) {
      this.meta = meta;
    }

    @Override
    public Map<String, Object> summary() {
      return Map.of(
          "name", value(meta.getName()),
          "description_present", meta.getDescription() != null && !meta.getDescription().isBlank(),
          "component_count", meta.nrActions(),
          "hop_count", meta.nrWorkflowHops());
    }

    @Override
    public Map<String, Object> apply(Map<String, Object> operation) throws Exception {
      String name = operationName(operation);
      return switch (name) {
        case "add_component" -> {
          String componentName = required(operation, "name");
          if (meta.findAction(componentName) != null) {
            throw McpException.conflict(
                "SEMANTIC_ENTITY_EXISTS", "Action name already exists: " + componentName);
          }
          int x = optionalCoordinate(operation, "x", 50);
          int y = optionalCoordinate(operation, "y", 50);
          ActionMeta action =
              componentAuthoring.createAction(
                  meta,
                  required(operation, "plugin_id"),
                  componentName,
                  operation.get("properties"),
                  operation.get("property_groups"),
                  x,
                  y);
          meta.addAction(action);
          yield Map.of(
              "operation", name,
              "component", componentName,
              "plugin_id", action.getAction().getPluginId(),
              "x", x,
              "y", y,
              "property_count", propertyCount(operation.get("properties")),
              "property_group_count", propertyCount(operation.get("property_groups")));
        }
        case "update_component" -> {
          String componentName = required(operation, "component");
          requireComponentUpdates(operation);
          ActionMeta action = requireAction(meta, componentName);
          componentAuthoring.updateComponent(
              action.getAction(), operation.get("properties"), operation.get("property_groups"));
          yield Map.of(
              "operation", name,
              "component", componentName,
              "plugin_id", action.getAction().getPluginId(),
              "property_count", propertyCount(operation.get("properties")),
              "property_group_count", propertyCount(operation.get("property_groups")));
        }
        case "set_name" -> {
          String next = required(operation, "value");
          String previous = value(meta.getName());
          meta.setNameSynchronizedWithFilename(false);
          meta.setName(next);
          yield change(name, "from", previous, "to", next);
        }
        case "set_description" -> {
          meta.setDescription(stringValue(operation, "value", 8192));
          yield Map.of("operation", name, "description_changed", true);
        }
        case "rename_component" -> {
          String component = required(operation, "component");
          String next = required(operation, "new_name");
          ActionMeta action = meta.findAction(component);
          if (action == null) throw new IllegalArgumentException("Unknown action: " + component);
          ActionMeta existing = meta.findAction(next);
          if (existing != null && existing != action) {
            throw McpException.conflict(
                "SEMANTIC_ENTITY_EXISTS", "Action name already exists: " + next);
          }
          action.setName(next);
          yield change(name, "from", component, "to", next);
        }
        case "move_component" -> {
          String component = required(operation, "component");
          ActionMeta action = requireAction(meta, component);
          int x = coordinate(operation, "x");
          int y = coordinate(operation, "y");
          action.setLocation(x, y);
          yield Map.of("operation", name, "component", component, "x", x, "y", y);
        }
        case "add_hop" -> {
          String from = required(operation, "from");
          String to = required(operation, "to");
          ActionMeta fromMeta = requireAction(meta, from);
          ActionMeta toMeta = requireAction(meta, to);
          if (fromMeta == toMeta) throw new IllegalArgumentException("A hop cannot target itself");
          if (meta.findWorkflowHop(fromMeta, toMeta, true) != null) {
            throw new IllegalArgumentException("Hop already exists: " + from + " -> " + to);
          }
          WorkflowHopMeta hop = new WorkflowHopMeta(fromMeta, toMeta);
          hop.setEnabled(optionalBoolean(operation, "enabled", true));
          if (operation.containsKey("unconditional")) {
            hop.setUnconditional(booleanValue(operation, "unconditional"));
          }
          if (operation.containsKey("evaluation")) {
            hop.setEvaluation(booleanValue(operation, "evaluation"));
          }
          meta.addWorkflowHop(meta.nrWorkflowHops(), hop);
          yield Map.of(
              "operation",
              name,
              "from",
              from,
              "to",
              to,
              "enabled",
              hop.isEnabled(),
              "evaluation",
              hop.isEvaluation(),
              "unconditional",
              hop.isUnconditional());
        }
        case "remove_hop" -> {
          String from = required(operation, "from");
          String to = required(operation, "to");
          WorkflowHopMeta hop =
              meta.findWorkflowHop(requireAction(meta, from), requireAction(meta, to), true);
          if (hop == null) throw new IllegalArgumentException("Unknown hop: " + from + " -> " + to);
          meta.removeWorkflowHop(hop);
          yield Map.of("operation", name, "from", from, "to", to);
        }
        case "set_hop_enabled" -> {
          String from = required(operation, "from");
          String to = required(operation, "to");
          ActionMeta fromMeta = meta.findAction(from);
          ActionMeta toMeta = meta.findAction(to);
          if (fromMeta == null || toMeta == null) {
            throw new IllegalArgumentException("Unknown action in hop: " + from + " -> " + to);
          }
          WorkflowHopMeta hop = meta.findWorkflowHop(fromMeta, toMeta, true);
          if (hop == null) throw new IllegalArgumentException("Unknown hop: " + from + " -> " + to);
          boolean enabled = booleanValue(operation, "enabled");
          hop.setEnabled(enabled);
          yield Map.of("operation", name, "from", from, "to", to, "enabled", enabled);
        }
        case "remove_component" -> {
          String component = required(operation, "component");
          ActionMeta action = requireAction(meta, component);
          meta.removeAction(meta.indexOfAction(action));
          yield Map.of("operation", name, "component", component);
        }
        default -> throw new IllegalArgumentException("Unsupported workflow mutation: " + name);
      };
    }

    @Override
    public String xml() throws Exception {
      return meta.getXml(variables);
    }
  }

  private static TransformMeta requireTransform(PipelineMeta meta, String name) {
    TransformMeta transform = meta.findTransform(name, null);
    if (transform == null) throw new IllegalArgumentException("Unknown transform: " + name);
    return transform;
  }

  private static ActionMeta requireAction(WorkflowMeta meta, String name) {
    ActionMeta action = meta.findAction(name);
    if (action == null) throw new IllegalArgumentException("Unknown action: " + name);
    return action;
  }

  private static String operationName(Map<String, Object> operation) {
    if (operation == null) throw new IllegalArgumentException("operation cannot be null");
    return required(operation, "operation").toLowerCase(Locale.ROOT);
  }

  private static String required(Map<String, Object> values, String key) {
    String value = stringValue(values, key);
    if (value.isBlank()) throw new IllegalArgumentException(key + " is required");
    return value;
  }

  private static String stringValue(Map<String, Object> values, String key) {
    return stringValue(values, key, 1024);
  }

  private static String stringValue(Map<String, Object> values, String key, int maxLength) {
    Object value = values.get(key);
    if (value == null) throw new IllegalArgumentException(key + " is required");
    String text = String.valueOf(value);
    if (text.length() > maxLength)
      throw new IllegalArgumentException(key + " cannot exceed " + maxLength + " characters");
    return text;
  }

  private static boolean booleanValue(Map<String, Object> values, String key) {
    Object value = values.get(key);
    if (value instanceof Boolean bool) return bool;
    if (value instanceof String text
        && ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text))) {
      return Boolean.parseBoolean(text);
    }
    throw new IllegalArgumentException(key + " must be a boolean");
  }

  private static boolean optionalBoolean(Map<String, Object> values, String key, boolean fallback) {
    return values.containsKey(key) ? booleanValue(values, key) : fallback;
  }

  private static int coordinate(Map<String, Object> values, String key) {
    Object value = values.get(key);
    int coordinate;
    if (value instanceof Number number) {
      coordinate = number.intValue();
    } else {
      try {
        coordinate = Integer.parseInt(String.valueOf(value));
      } catch (RuntimeException e) {
        throw new IllegalArgumentException(key + " must be an integer", e);
      }
    }
    if (coordinate < 0 || coordinate > 1_000_000) {
      throw new IllegalArgumentException(key + " must be between 0 and 1000000");
    }
    return coordinate;
  }

  private static int optionalCoordinate(Map<String, Object> values, String key, int fallback) {
    return values.containsKey(key) ? coordinate(values, key) : fallback;
  }

  private static int propertyCount(Object value) {
    return value instanceof Map<?, ?> map ? map.size() : 0;
  }

  private static void requireComponentUpdates(Map<String, Object> operation) {
    if (propertyCount(operation.get("properties")) == 0
        && propertyCount(operation.get("property_groups")) == 0) {
      throw new IllegalArgumentException("update_component requires properties or property_groups");
    }
  }

  private static Map<String, Object> change(
      String operation, String firstKey, Object first, String secondKey, Object second) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("operation", operation);
    result.put(firstKey, first);
    result.put(secondKey, second);
    return result;
  }

  private static String value(String value) {
    return value == null ? "" : value;
  }

  private static final class MutationRecord {
    private final String id;
    private final String relative;
    private final String kind;
    private final Path target;
    private final Path backup;
    private final boolean existed;
    private final String oldHash;

    @SuppressWarnings("unused")
    private final String newHash;

    private final Instant expiresAt;

    private boolean rolledBack;

    private MutationRecord(
        String id,
        String relative,
        String kind,
        Path target,
        Path backup,
        boolean existed,
        String oldHash,
        String newHash,
        Instant expiresAt) {
      this.id = id;
      this.relative = relative;
      this.kind = kind;
      this.target = target;
      this.backup = backup;
      this.existed = existed;
      this.oldHash = oldHash;
      this.newHash = newHash;
      this.expiresAt = expiresAt;
    }
  }
}
