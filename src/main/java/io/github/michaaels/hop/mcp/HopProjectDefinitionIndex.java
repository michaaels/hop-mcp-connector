package io.github.michaaels.hop.mcp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.w3c.dom.Document;

/** Bounded, incremental in-memory index of project pipeline and workflow definitions. */
final class HopProjectDefinitionIndex {
  static final int MAX_TABLES = 200;
  static final int MAX_REFERENCES = 200;

  @FunctionalInterface
  interface DefinitionScanner {
    BoundedProjectWalker.ScanResult scan(int resultLimit) throws IOException;
  }

  @FunctionalInterface
  interface DefinitionReader {
    byte[] read(Path path, long maximumBytes) throws IOException;
  }

  enum ReferenceSource {
    NATIVE,
    METADATA_PROPERTY,
    TEXT_FALLBACK
  }

  record MetadataReference(String type, String name, String component, ReferenceSource source) {}

  record DefinitionFileStamp(long size, FileTime lastModified, Object fileKey) {}

  record Entry(
      String path,
      String kind,
      Map<String, Object> inspection,
      Set<String> tables,
      Set<String> references,
      List<MetadataReference> metadataReferences,
      long bytes,
      DefinitionFileStamp stamp,
      boolean tablesTruncated,
      boolean referencesTruncated,
      boolean metadataReferencesTruncated) {}

  record RefreshMetrics(
      long totalMillis,
      long scanMillis,
      int regularFilesExamined,
      int definitionsFound,
      long bytesRead,
      int cacheHits,
      int cacheMisses,
      boolean truncated) {
    private static RefreshMetrics empty() {
      return new RefreshMetrics(0, 0, 0, 0, 0, 0, 0, false);
    }
  }

  record Snapshot(
      Map<String, Entry> definitions,
      boolean truncated,
      long indexedBytes,
      int cacheHits,
      int cacheMisses,
      long generation,
      int regularFilesExamined,
      int definitionsFound,
      long bytesRead,
      long scanMillis,
      long totalMillis) {}

  private final ProjectFiles files;
  private final HopMetadataReferenceExtractor metadataReferenceExtractor;
  private final DefinitionScanner definitionScanner;
  private final DefinitionReader definitionReader;
  private final Runnable refreshWaiterObserver;
  private final Object refreshLock = new Object();
  private final AtomicLong invalidationGeneration = new AtomicLong();
  private final AtomicLong requestSequence = new AtomicLong();
  private final AtomicLong refreshCount = new AtomicLong();

  private volatile Map<String, Entry> cache = Map.of();
  private volatile Snapshot publishedSnapshot;
  private volatile RefreshMetrics lastRefresh = RefreshMetrics.empty();
  private long coveredRequestSequence;
  private CompletableFuture<Snapshot> refreshInFlight;

  HopProjectDefinitionIndex(ProjectFiles files) {
    this(files, null, null);
  }

  HopProjectDefinitionIndex(
      ProjectFiles files, IHopMetadataProvider metadataProvider, IVariables variables) {
    this(files, new HopMetadataReferenceExtractor(metadataProvider, variables));
  }

  HopProjectDefinitionIndex(
      ProjectFiles files, HopMetadataReferenceExtractor metadataReferenceExtractor) {
    this(files, metadataReferenceExtractor, files::definitionScan, files::readBytes);
  }

  HopProjectDefinitionIndex(
      ProjectFiles files,
      HopMetadataReferenceExtractor metadataReferenceExtractor,
      DefinitionScanner definitionScanner,
      DefinitionReader definitionReader) {
    this(files, metadataReferenceExtractor, definitionScanner, definitionReader, () -> {});
  }

  HopProjectDefinitionIndex(
      ProjectFiles files,
      HopMetadataReferenceExtractor metadataReferenceExtractor,
      DefinitionScanner definitionScanner,
      DefinitionReader definitionReader,
      Runnable refreshWaiterObserver) {
    this.files = Objects.requireNonNull(files);
    this.metadataReferenceExtractor = Objects.requireNonNull(metadataReferenceExtractor);
    this.definitionScanner = Objects.requireNonNull(definitionScanner);
    this.definitionReader = Objects.requireNonNull(definitionReader);
    this.refreshWaiterObserver = Objects.requireNonNull(refreshWaiterObserver);
  }

  Snapshot snapshot() throws Exception {
    long request;
    CompletableFuture<Snapshot> flight;
    boolean refreshOwner = false;
    synchronized (refreshLock) {
      request = requestSequence.incrementAndGet();
      Snapshot published = publishedSnapshot;
      if (published != null
          && request <= coveredRequestSequence
          && published.generation() == invalidationGeneration.get()) {
        return published;
      }
      flight = refreshInFlight;
      if (flight == null) {
        flight = new CompletableFuture<>();
        refreshInFlight = flight;
        refreshOwner = true;
      }
    }

    if (!refreshOwner) {
      refreshWaiterObserver.run();
      return await(flight);
    }

    try {
      while (true) {
        long generation = invalidationGeneration.get();
        Snapshot refreshed = refresh(generation);
        synchronized (refreshLock) {
          if (generation != invalidationGeneration.get()) continue;
          cache = refreshed.definitions();
          publishedSnapshot = refreshed;
          coveredRequestSequence = requestSequence.get();
          refreshInFlight = null;
          flight.complete(refreshed);
          return refreshed;
        }
      }
    } catch (Throwable failure) {
      synchronized (refreshLock) {
        if (refreshInFlight == flight) refreshInFlight = null;
      }
      flight.completeExceptionally(failure);
      if (failure instanceof Exception exception) throw exception;
      throw (Error) failure;
    }
  }

  void invalidate(Path path) {
    Objects.requireNonNull(path, "path");
    Path normalized = (path.isAbsolute() ? path : files.root().resolve(path)).normalize();
    if (!normalized.startsWith(files.root())) {
      throw new IllegalArgumentException("Invalidated path must remain inside the project root");
    }
    String relative = files.relative(normalized);
    synchronized (refreshLock) {
      invalidationGeneration.incrementAndGet();
      Map<String, Entry> next = new LinkedHashMap<>(cache);
      next.remove(relative);
      cache = Collections.unmodifiableMap(next);
    }
  }

  Entry readSingle(Path path) throws Exception {
    BasicFileAttributes attributes =
        java.nio.file.Files.readAttributes(
            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (attributes.size() > ProjectFiles.MAX_READ_BYTES) {
      throw new IllegalArgumentException("Definition exceeds the configured read limit");
    }
    return read(
        path, files.relative(path), stamp(attributes), ProjectFiles.MAX_READ_BYTES, new long[1]);
  }

  Map<String, Object> metrics() {
    Map<String, Object> result = new LinkedHashMap<>();
    long generation;
    Map<String, Entry> entries;
    synchronized (refreshLock) {
      generation = invalidationGeneration.get();
      entries = cache;
    }
    int metadataReferences = 0;
    int tableReferences = 0;
    int definitionReferences = 0;
    long sourceBytesIndexed = 0L;
    for (Entry entry : entries.values()) {
      metadataReferences += entry.metadataReferences().size();
      tableReferences += entry.tables().size();
      definitionReferences += entry.references().size();
      sourceBytesIndexed += entry.bytes();
    }
    RefreshMetrics last = lastRefresh;
    result.put("generation", generation);
    result.put("entries", entries.size());
    result.put("source_bytes_indexed", sourceBytesIndexed);
    result.put("metadata_references", metadataReferences);
    result.put("table_references", tableReferences);
    result.put("definition_references", definitionReferences);
    result.put("refreshes", refreshCount.get());
    result.put(
        "last_refresh",
        Map.of(
            "total_ms", last.totalMillis(),
            "scan_ms", last.scanMillis(),
            "regular_files_examined", last.regularFilesExamined(),
            "definitions_found", last.definitionsFound(),
            "bytes_read", last.bytesRead(),
            "cache_hits", last.cacheHits(),
            "cache_misses", last.cacheMisses(),
            "truncated", last.truncated()));
    return Collections.unmodifiableMap(result);
  }

  private Snapshot refresh(long generation) throws Exception {
    refreshCount.incrementAndGet();
    long started = System.nanoTime();
    long scanStarted = System.nanoTime();
    BoundedProjectWalker.ScanResult scan = definitionScanner.scan(ProjectFiles.MAX_SCAN_FILES);
    long scanMillis = elapsedMillis(scanStarted);
    List<BoundedProjectWalker.ScannedFile> scannedFiles = new ArrayList<>(scan.files());
    scannedFiles.sort(Comparator.comparing(item -> files.relative(item.path())));

    Map<String, Entry> previous = cache;
    Map<String, Entry> next = new LinkedHashMap<>();
    boolean truncated = scan.scanLimitReached() || scan.resultsTruncated();
    long indexedBytes = 0L;
    long[] bytesRead = {0};
    int cacheHits = 0;
    int cacheMisses = 0;

    for (BoundedProjectWalker.ScannedFile scannedFile : scannedFiles) {
      Path path = scannedFile.path();
      DefinitionFileStamp stamp = stamp(scannedFile);
      long size = stamp.size();
      if (size > ProjectFiles.MAX_READ_BYTES
          || size > ProjectFiles.MAX_TOTAL_SCAN_BYTES - indexedBytes) {
        truncated = true;
        continue;
      }

      String relative = files.relative(path);
      Entry cached = previous.get(relative);
      Entry entry;
      if (cached != null && cached.stamp().equals(stamp)) {
        entry = cached;
        cacheHits++;
      } else {
        cacheMisses++;
        try {
          entry =
              read(
                  path,
                  relative,
                  stamp,
                  ProjectFiles.MAX_TOTAL_SCAN_BYTES - indexedBytes,
                  bytesRead);
        } catch (Exception ignored) {
          truncated = true;
          if (cached == null || java.nio.file.Files.notExists(path, LinkOption.NOFOLLOW_LINKS))
            continue;
          // Keep the last known-good entry until a later refresh can parse this file.
          entry = cached;
        }
      }
      if (entry.bytes() > ProjectFiles.MAX_TOTAL_SCAN_BYTES - indexedBytes) {
        truncated = true;
        continue;
      }
      indexedBytes += entry.bytes();
      next.put(relative, entry);
    }

    Map<String, Entry> immutableDefinitions =
        Collections.unmodifiableMap(new LinkedHashMap<>(next));
    long totalMillis = elapsedMillis(started);
    RefreshMetrics metrics =
        new RefreshMetrics(
            totalMillis,
            scanMillis,
            scan.regularFilesExamined(),
            scannedFiles.size(),
            bytesRead[0],
            cacheHits,
            cacheMisses,
            truncated);
    lastRefresh = metrics;
    return new Snapshot(
        immutableDefinitions,
        truncated,
        indexedBytes,
        cacheHits,
        cacheMisses,
        generation,
        scan.regularFilesExamined(),
        scannedFiles.size(),
        bytesRead[0],
        scanMillis,
        totalMillis);
  }

  private Entry read(
      Path path, String relative, DefinitionFileStamp stamp, long maximumBytes, long[] bytesRead)
      throws Exception {
    byte[] content = definitionReader.read(path, maximumBytes);
    bytesRead[0] += content.length;
    String xml =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(content))
            .toString();
    Document document = HopXml.parse(xml);
    Map<String, Object> inspection = immutableMap(HopXml.inspect(relative, document));
    Set<String> tables = HopXml.findTables(xml, null, MAX_TABLES + 1);
    Set<String> references = HopXml.references(xml, MAX_REFERENCES + 1);
    HopMetadataReferenceExtractor.Result metadata =
        metadataReferenceExtractor.extract(relative, document);
    return new Entry(
        relative,
        kind(relative),
        inspection,
        Collections.unmodifiableSet(new LinkedHashSet<>(tables)),
        Collections.unmodifiableSet(new LinkedHashSet<>(references)),
        List.copyOf(metadata.references()),
        content.length,
        stamp,
        tables.size() > MAX_TABLES,
        references.size() > MAX_REFERENCES,
        metadata.truncated());
  }

  private static DefinitionFileStamp stamp(BoundedProjectWalker.ScannedFile file) {
    return new DefinitionFileStamp(file.size(), file.lastModified(), file.fileKey());
  }

  private static DefinitionFileStamp stamp(BasicFileAttributes attributes) {
    return new DefinitionFileStamp(
        attributes.size(), attributes.lastModifiedTime(), attributes.fileKey());
  }

  private static Snapshot await(CompletableFuture<Snapshot> future) throws Exception {
    try {
      return future.get();
    } catch (ExecutionException failed) {
      Throwable cause = failed.getCause();
      if (cause instanceof Exception exception) throw exception;
      if (cause instanceof Error error) throw error;
      throw new IllegalStateException("Project definition refresh failed", cause);
    }
  }

  private static long elapsedMillis(long startedNanos) {
    return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
  }

  private static Map<String, Object> immutableMap(Map<String, Object> source) {
    Map<String, Object> result = new LinkedHashMap<>();
    source.forEach((key, value) -> result.put(key, immutableValue(value)));
    return Collections.unmodifiableMap(result);
  }

  private static Object immutableValue(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> result = new LinkedHashMap<>();
      map.forEach((key, nested) -> result.put(String.valueOf(key), immutableValue(nested)));
      return Collections.unmodifiableMap(result);
    }
    if (value instanceof List<?> list) {
      List<Object> result = new ArrayList<>(list.size());
      for (Object nested : list) result.add(immutableValue(nested));
      return List.copyOf(result);
    }
    if (value instanceof Set<?> set) {
      Set<Object> result = new LinkedHashSet<>();
      for (Object nested : set) result.add(immutableValue(nested));
      return Collections.unmodifiableSet(result);
    }
    return value;
  }

  private static String kind(String path) {
    String lower = path.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".hpl")) return "pipeline";
    if (lower.endsWith(".hwf")) return "workflow";
    return "definition";
  }
}
