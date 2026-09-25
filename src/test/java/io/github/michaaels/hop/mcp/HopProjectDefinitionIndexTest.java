package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HopProjectDefinitionIndexTest {
  private static final byte[] PIPELINE = pipeline("A").getBytes(StandardCharsets.UTF_8);

  @TempDir Path project;

  @Test
  void reusesUnchangedDefinitionsAndReindexesOnlyChangedFiles() throws Exception {
    Path first = project.resolve("first.hpl");
    Path second = project.resolve("second.hwf");
    Files.writeString(first, pipeline("A"));
    Files.writeString(second, workflow("B"));
    setModified(first, 1_700_000_000_000L);

    HopProjectDefinitionIndex index = new HopProjectDefinitionIndex(new ProjectFiles(project));
    HopProjectDefinitionIndex.Snapshot initial = index.snapshot();
    assertEquals(2, initial.definitions().size());
    assertEquals(0, initial.cacheHits());
    assertEquals(2, initial.cacheMisses());

    HopProjectDefinitionIndex.Snapshot warm = index.snapshot();
    assertNotSame(initial, warm);
    assertEquals(2, warm.cacheHits());
    assertEquals(0, warm.cacheMisses());
    Files.writeString(first, pipeline("Z"));
    setModified(first, 1_700_000_002_000L);
    HopProjectDefinitionIndex.Snapshot changed = index.snapshot();
    assertEquals(1, changed.cacheHits());
    assertEquals(1, changed.cacheMisses());
    assertNotEquals(
        initial.definitions().get("first.hpl").inspection(),
        changed.definitions().get("first.hpl").inspection());
  }

  @Test
  void concurrentReadersShareOneRefreshAndOneImmutableSnapshot() throws Exception {
    Files.writeString(project.resolve("one.hpl"), pipeline("A"));
    BlockingProjectFiles files = new BlockingProjectFiles(project);
    CountDownLatch joinedRefresh = new CountDownLatch(19);
    HopProjectDefinitionIndex index = newIndex(files, joinedRefresh::countDown);
    ExecutorService pool = Executors.newFixedThreadPool(20);
    try {
      CyclicBarrier start = new CyclicBarrier(20);
      List<Future<HopProjectDefinitionIndex.Snapshot>> futures = new ArrayList<>();
      for (int i = 0; i < 20; i++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return index.snapshot();
                }));
      }
      assertTrue(files.scanEntered.await(5, TimeUnit.SECONDS));
      assertTrue(joinedRefresh.await(5, TimeUnit.SECONDS));
      files.releaseScan.countDown();
      HopProjectDefinitionIndex.Snapshot first = futures.getFirst().get(5, TimeUnit.SECONDS);
      for (Future<HopProjectDefinitionIndex.Snapshot> future : futures) {
        assertSame(first, future.get(5, TimeUnit.SECONDS));
      }
      assertEquals(1, files.scanCalls.get());
      assertEquals(1L, index.metrics().get("refreshes"));
      assertThrows(UnsupportedOperationException.class, () -> first.definitions().clear());
      assertThrows(
          UnsupportedOperationException.class,
          () -> first.definitions().get("one.hpl").inspection().clear());
    } finally {
      files.releaseScan.countDown();
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void invalidationDuringRefreshCausesASecondCurrentGenerationRefresh() throws Exception {
    Path file = project.resolve("flow.hpl");
    Files.writeString(file, pipeline("A"));
    setModified(file, 1_700_000_000_000L);
    BlockingProjectFiles files = new BlockingProjectFiles(project);
    HopProjectDefinitionIndex index = newIndex(files);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<HopProjectDefinitionIndex.Snapshot> refresh = pool.submit(index::snapshot);
      assertTrue(files.scanEntered.await(5, TimeUnit.SECONDS));
      Files.writeString(file, pipeline("B"));
      setModified(file, 1_700_000_002_000L);
      index.invalidate(file);
      files.releaseScan.countDown();

      HopProjectDefinitionIndex.Snapshot current = refresh.get(5, TimeUnit.SECONDS);
      assertEquals(1, current.generation());
      assertEquals(2, files.scanCalls.get());
      assertEquals(1, current.definitions().size());
      assertEquals(1, current.cacheMisses());
      assertTrue(current.definitions().get("flow.hpl").inspection().toString().contains("B"));
    } finally {
      files.releaseScan.countDown();
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void fileKeyAndMtimeDetectSameSizeChangesAndNullFileKeysRemainSupported() throws Exception {
    FileTime time = FileTime.fromMillis(1_700_000_000_000L);
    Path path = project.resolve("synthetic.hpl");
    SyntheticProjectFiles files = new SyntheticProjectFiles(project, List.of(), 0);
    files.setScan(List.of(scanned(path, PIPELINE.length, time, null)), 1, false);
    files.setContent(PIPELINE);
    HopProjectDefinitionIndex index = newIndex(files);
    HopProjectDefinitionIndex.Snapshot first = index.snapshot();
    assertEquals(null, first.definitions().get("synthetic.hpl").stamp().fileKey());

    files.setContent(pipeline("B").getBytes(StandardCharsets.UTF_8));
    files.setScan(List.of(scanned(path, PIPELINE.length, time, "file-key-2")), 1, false);
    HopProjectDefinitionIndex.Snapshot replaced = index.snapshot();
    assertEquals(1, replaced.cacheMisses());
    assertNotSame(
        first.definitions().get("synthetic.hpl"), replaced.definitions().get("synthetic.hpl"));

    files.setContent(pipeline("C").getBytes(StandardCharsets.UTF_8));
    files.setScan(
        List.of(
            scanned(path, PIPELINE.length, FileTime.fromMillis(time.toMillis() + 2_000L), null)),
        1,
        false);
    HopProjectDefinitionIndex.Snapshot nullKeyMtimeChange = index.snapshot();
    assertEquals(1, nullKeyMtimeChange.cacheMisses());
    assertEquals(null, nullKeyMtimeChange.definitions().get("synthetic.hpl").stamp().fileKey());
  }

  @Test
  void deletionAndNewDefinitionsAreReflectedInTheNextSnapshot() throws Exception {
    Path first = project.resolve("first.hpl");
    Files.writeString(first, pipeline("A"));
    HopProjectDefinitionIndex index = new HopProjectDefinitionIndex(new ProjectFiles(project));
    assertEquals(Set.of("first.hpl"), index.snapshot().definitions().keySet());

    Files.delete(first);
    Files.writeString(project.resolve("second.hwf"), workflow("B"));
    HopProjectDefinitionIndex.Snapshot changed = index.snapshot();
    assertEquals(Set.of("second.hwf"), changed.definitions().keySet());
    assertFalse(changed.truncated());
  }

  @Test
  void deletionAfterWalkerVisitDoesNotPublishTheLastKnownGoodEntry() throws Exception {
    Path file = project.resolve("flow.hpl");
    Files.writeString(file, pipeline("A"));
    setModified(file, 1_700_000_000_000L);
    BlockingProjectFiles files = new BlockingProjectFiles(project, 2);
    HopProjectDefinitionIndex index = newIndex(files);
    assertEquals(Set.of("flow.hpl"), index.snapshot().definitions().keySet());
    Files.writeString(file, pipeline("B"));
    setModified(file, 1_700_000_002_000L);

    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<HopProjectDefinitionIndex.Snapshot> refresh = pool.submit(index::snapshot);
      assertTrue(files.scanEntered.await(5, TimeUnit.SECONDS));
      Files.delete(file);
      files.releaseScan.countDown();

      HopProjectDefinitionIndex.Snapshot deleted = refresh.get(5, TimeUnit.SECONDS);
      assertTrue(deleted.truncated());
      assertTrue(deleted.definitions().isEmpty());
      assertTrue(index.snapshot().definitions().isEmpty());
    } finally {
      files.releaseScan.countDown();
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void malformedChangedFileKeepsTheLastKnownGoodEntryAndMarksTruncation() throws Exception {
    Path file = project.resolve("flow.hpl");
    Files.writeString(file, pipeline("A"));
    setModified(file, 1_700_000_000_000L);
    HopProjectDefinitionIndex index = new HopProjectDefinitionIndex(new ProjectFiles(project));
    HopProjectDefinitionIndex.Snapshot good = index.snapshot();

    Files.writeString(file, "<pipeline><broken>");
    setModified(file, 1_700_000_002_000L);
    HopProjectDefinitionIndex.Snapshot partial = index.snapshot();
    assertTrue(partial.truncated());
    assertSame(good.definitions().get("flow.hpl"), partial.definitions().get("flow.hpl"));
    assertEquals(1, partial.cacheMisses());
  }

  @Test
  void wholeScanFailureDoesNotCorruptTheLastKnownGoodCacheAndCanRetry() throws Exception {
    SyntheticProjectFiles files = syntheticFiles(1, 1);
    HopProjectDefinitionIndex index = newIndex(files);
    HopProjectDefinitionIndex.Snapshot good = index.snapshot();
    files.failNextScan = true;
    index.invalidate(project.resolve("not-present.hpl"));

    assertThrows(IOException.class, index::snapshot);
    assertEquals(1, index.metrics().get("entries"));
    HopProjectDefinitionIndex.Snapshot retried = index.snapshot();
    assertEquals(1, retried.cacheHits());
    assertEquals(0, retried.cacheMisses());
    assertEquals(good.definitions().keySet(), retried.definitions().keySet());
  }

  @Test
  void metricsTrackRefreshAttemptsAndCurrentIndexedBytesAcrossInvalidation() throws Exception {
    SyntheticProjectFiles files = syntheticFiles(1, 1);
    HopProjectDefinitionIndex index = newIndex(files);
    HopProjectDefinitionIndex.Snapshot initial = index.snapshot();
    assertEquals((long) PIPELINE.length, index.metrics().get("source_bytes_indexed"));

    index.invalidate(project.resolve("d00000.hpl"));
    assertEquals(0, index.metrics().get("entries"));
    assertEquals(0L, index.metrics().get("source_bytes_indexed"));

    files.failNextScan = true;
    assertThrows(IOException.class, index::snapshot);
    assertEquals(2L, index.metrics().get("refreshes"));
    assertEquals(0L, index.metrics().get("source_bytes_indexed"));

    HopProjectDefinitionIndex.Snapshot rebuilt = index.snapshot();
    assertEquals(initial.generation() + 1, rebuilt.generation());
    assertEquals(1, rebuilt.definitions().size());
    assertEquals(3L, index.metrics().get("refreshes"));
    assertEquals((long) PIPELINE.length, index.metrics().get("source_bytes_indexed"));
  }

  @Test
  void definitionBoundsCoverFiveThousandAndTenOrTwentyThousandAvailable() throws Exception {
    SyntheticProjectFiles exact = syntheticFiles(5_000, 5_000);
    HopProjectDefinitionIndex.Snapshot exactSnapshot = newIndex(exact).snapshot();
    assertEquals(5_000, exactSnapshot.definitions().size());
    assertFalse(exactSnapshot.truncated());
    assertEquals(5_000, exact.readCalls.get());

    for (int available : List.of(10_000, 20_000)) {
      SyntheticProjectFiles files = syntheticFiles(available, available);
      HopProjectDefinitionIndex.Snapshot snapshot = newIndex(files).snapshot();
      assertEquals(ProjectFiles.MAX_SCAN_FILES, snapshot.definitions().size());
      assertTrue(snapshot.truncated());
      assertEquals(ProjectFiles.MAX_SCAN_FILES, files.readCalls.get());
      assertTrue(snapshot.indexedBytes() <= ProjectFiles.MAX_TOTAL_SCAN_BYTES);
    }
  }

  @Test
  void mixedTwentyThousandFileProjectStillIndexesAllFiveThousandDefinitions() throws Exception {
    SyntheticProjectFiles files = syntheticFiles(5_000, 20_000);
    HopProjectDefinitionIndex.Snapshot snapshot = newIndex(files).snapshot();
    assertEquals(20_000, snapshot.regularFilesExamined());
    assertEquals(5_000, snapshot.definitions().size());
    assertFalse(snapshot.truncated());
    assertEquals(5_000, files.readCalls.get());
  }

  private SyntheticProjectFiles syntheticFiles(int definitions, int regularFilesExamined)
      throws IOException {
    List<BoundedProjectWalker.ScannedFile> scanned = new ArrayList<>(definitions);
    FileTime time = FileTime.fromMillis(1_700_000_000_000L);
    byte[] contents = pipeline("A").getBytes(StandardCharsets.UTF_8);
    for (int i = 0; i < definitions; i++) {
      scanned.add(
          scanned(project.resolve(String.format("d%05d.hpl", i)), contents.length, time, "f" + i));
    }
    SyntheticProjectFiles files = new SyntheticProjectFiles(project, scanned, regularFilesExamined);
    files.setContent(contents);
    return files;
  }

  private HopProjectDefinitionIndex newIndex(SyntheticProjectFiles synthetic) {
    return new HopProjectDefinitionIndex(
        synthetic.files,
        new HopMetadataReferenceExtractor(null, null),
        synthetic::definitionScan,
        synthetic::readBytes);
  }

  private HopProjectDefinitionIndex newIndex(BlockingProjectFiles blocking) {
    return newIndex(blocking, () -> {});
  }

  private HopProjectDefinitionIndex newIndex(
      BlockingProjectFiles blocking, Runnable refreshWaiterObserver) {
    return new HopProjectDefinitionIndex(
        blocking.files,
        new HopMetadataReferenceExtractor(null, null),
        blocking::definitionScan,
        blocking.files::readBytes,
        refreshWaiterObserver);
  }

  private static BoundedProjectWalker.ScannedFile scanned(
      Path path, long size, FileTime modified, Object fileKey) {
    return new BoundedProjectWalker.ScannedFile(path, size, modified, fileKey);
  }

  private static void setModified(Path path, long epochMillis) throws IOException {
    Files.setLastModifiedTime(path, FileTime.fromMillis(epochMillis));
  }

  private static String pipeline(String value) {
    return "<pipeline><info><name>flow</name></info><transform><name>"
        + value
        + "</name><type>Dummy</type></transform></pipeline>";
  }

  private static String workflow(String value) {
    return "<workflow><info><name>flow</name></info><action><name>"
        + value
        + "</name><type>Dummy</type></action></workflow>";
  }

  private static final class SyntheticProjectFiles {
    private final ProjectFiles files;
    private volatile List<BoundedProjectWalker.ScannedFile> scanned;
    private volatile int regularFilesExamined;
    private volatile boolean scanLimitReached;
    private volatile byte[] content = PIPELINE;
    private volatile boolean failNextScan;
    private final AtomicInteger scanCalls = new AtomicInteger();
    private final AtomicInteger readCalls = new AtomicInteger();

    SyntheticProjectFiles(
        Path root, List<BoundedProjectWalker.ScannedFile> scanned, int regularFilesExamined)
        throws IOException {
      files = new ProjectFiles(root);
      setScan(scanned, regularFilesExamined, false);
    }

    final void setScan(
        List<BoundedProjectWalker.ScannedFile> newScanned,
        int newRegularFilesExamined,
        boolean newScanLimitReached) {
      scanned = List.copyOf(newScanned);
      regularFilesExamined = newRegularFilesExamined;
      scanLimitReached = newScanLimitReached;
    }

    final void setContent(byte[] newContent) {
      content = newContent.clone();
    }

    BoundedProjectWalker.ScanResult definitionScan(int resultLimit) throws IOException {
      scanCalls.incrementAndGet();
      if (failNextScan) {
        failNextScan = false;
        throw new IOException("synthetic scan failure");
      }
      int returned = Math.min(resultLimit, scanned.size());
      return new BoundedProjectWalker.ScanResult(
          scanned.subList(0, returned),
          regularFilesExamined,
          regularFilesExamined,
          scanLimitReached,
          scanned.size() > returned);
    }

    byte[] readBytes(Path path, long maximumBytes) throws IOException {
      readCalls.incrementAndGet();
      if (content.length > maximumBytes) throw new IOException("synthetic byte limit");
      return content.clone();
    }
  }

  private static final class BlockingProjectFiles {
    private final ProjectFiles files;
    private final int blockedScan;
    private final AtomicInteger scanCalls = new AtomicInteger();
    private final CountDownLatch scanEntered = new CountDownLatch(1);
    private final CountDownLatch releaseScan = new CountDownLatch(1);

    BlockingProjectFiles(Path root) throws IOException {
      this(root, 1);
    }

    BlockingProjectFiles(Path root, int blockedScan) throws IOException {
      files = new ProjectFiles(root);
      this.blockedScan = blockedScan;
    }

    BoundedProjectWalker.ScanResult definitionScan(int resultLimit) throws IOException {
      BoundedProjectWalker.ScanResult result = files.definitionScan(resultLimit);
      if (scanCalls.incrementAndGet() == blockedScan) {
        scanEntered.countDown();
        try {
          if (!releaseScan.await(5, TimeUnit.SECONDS))
            throw new IOException("scan latch timed out");
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IOException("scan interrupted", interrupted);
        }
      }
      return result;
    }
  }
}
