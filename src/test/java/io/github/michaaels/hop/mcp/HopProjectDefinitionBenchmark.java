package io.github.michaaels.hop.mcp;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;

/** Same-runner benchmark harness. It uses only APIs present in the 2.2.1 baseline. */
public final class HopProjectDefinitionBenchmark {
  private static final int[] STANDARD_SIZES = {1_000, 2_500, 5_000};
  private static final int WARM_RUNS = 5;
  private static final int CHAIN_LENGTH = 63;

  private HopProjectDefinitionBenchmark() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 3) {
      throw new IllegalArgumentException("expected fixture-root, output-file, and run-mode");
    }
    Path fixtureRoot = Path.of(args[0]).toAbsolutePath().normalize();
    Path output = Path.of(args[1]).toAbsolutePath().normalize();
    String mode = args[2];
    if (!mode.equals("baseline") && !mode.equals("current")) {
      throw new IllegalArgumentException("run-mode must be baseline or current");
    }
    HopEnvironment.init();
    Files.createDirectories(fixtureRoot);

    Path warmup = coreProject(fixtureRoot, "warmup-100", 100);
    HopProjectDefinitionIndex warmupIndex = newIndex(new ProjectFiles(warmup));
    warmupIndex.snapshot();
    warmupIndex.snapshot();

    List<Row> rows = new ArrayList<>();
    for (int size : STANDARD_SIZES) {
      Path project = coreProject(fixtureRoot, "definitions-" + size, size);
      rows.addAll(benchmarkStandardProject(project, size));
    }

    boolean currentRun = mode.equals("current");
    Path mixed = mixedProject(fixtureRoot, 20_000, 5_000);
    rows.add(
        benchmarkBoundedScenario(
            mixed, "mixed-20k-files-5k-definitions", 20_000, 5_000, currentRun));
    for (int size : new int[] {10_000, 20_000}) {
      Path project = definitionsOnlyProject(fixtureRoot, "available-definitions-" + size, size);
      rows.add(
          benchmarkBoundedScenario(
              project, "available-definitions-" + size, size, 5_000, currentRun));
    }

    Files.createDirectories(output.getParent());
    Files.writeString(output, json(mode, rows), StandardCharsets.UTF_8);
  }

  private static List<Row> benchmarkStandardProject(Path project, int expectedDefinitions)
      throws Exception {
    ProjectFiles files = new ProjectFiles(project);
    HopProjectDefinitionIndex index = newIndex(files);
    List<Row> rows = new ArrayList<>();

    Long beforeRefreshes = refreshCount(index);
    long started = System.nanoTime();
    HopProjectDefinitionIndex.Snapshot cold = index.snapshot();
    long coldNanos = System.nanoTime() - started;
    Long afterRefreshes = refreshCount(index);
    assertIndex(cold, expectedDefinitions, false, "cold index");
    assertDefinitionPaths(project, cold, true);
    rows.add(
        row(
            project.getFileName().toString(),
            "cold",
            coldNanos,
            cold,
            files,
            expectedDefinitions,
            delta(beforeRefreshes, afterRefreshes),
            null));

    long[] warmNanos = new long[WARM_RUNS];
    HopProjectDefinitionIndex.Snapshot warm = null;
    beforeRefreshes = afterRefreshes;
    for (int i = 0; i < WARM_RUNS; i++) {
      started = System.nanoTime();
      warm = index.snapshot();
      warmNanos[i] = System.nanoTime() - started;
    }
    afterRefreshes = refreshCount(index);
    java.util.Arrays.sort(warmNanos);
    assertIndex(warm, expectedDefinitions, false, "warm index");
    assertDefinitionPaths(project, warm, true);
    if (warm.cacheHits() != expectedDefinitions || warm.cacheMisses() != 0) {
      throw new AssertionError("warm index must reuse all unchanged definitions");
    }
    rows.add(
        row(
            project.getFileName().toString(),
            "warm_median_x5",
            warmNanos[WARM_RUNS / 2],
            warm,
            files,
            expectedDefinitions,
            delta(beforeRefreshes, afterRefreshes),
            null));

    Path changed = project.resolve(String.format("filler-%05d.hpl", expectedDefinitions - 1));
    byte[] original = Files.readAllBytes(changed);
    beforeRefreshes = afterRefreshes;
    HopProjectDefinitionIndex.Snapshot incremental;
    long incrementalNanos;
    try {
      Files.write(changed, appendSpace(original));
      started = System.nanoTime();
      incremental = index.snapshot();
      incrementalNanos = System.nanoTime() - started;
      afterRefreshes = refreshCount(index);
      assertIndex(incremental, expectedDefinitions, false, "one-file-changed index");
      assertDefinitionPaths(project, incremental, true);
      if (incremental.cacheHits() != expectedDefinitions - 1
          || incremental.cacheMisses() != 1
          || incremental.definitions().get(files.relative(changed)).bytes()
              != Files.size(changed)) {
        throw new AssertionError("one-file-changed refresh must reparse only the changed file");
      }
    } finally {
      Files.write(changed, original);
    }
    rows.add(
        row(
            project.getFileName().toString(),
            "one_file_changed",
            incrementalNanos,
            incremental,
            files,
            expectedDefinitions,
            delta(beforeRefreshes, afterRefreshes),
            null));

    Map<String, Object> impact =
        new HopImpactAnalysisService(new ProjectFiles(project))
            .analyze("DWH.DIM_SITE", null, null, 64, 500, 200);
    int nodeCount = ((Number) impact.get("node_count")).intValue();
    if (nodeCount != CHAIN_LENGTH + 1 || Boolean.TRUE.equals(impact.get("results_truncated"))) {
      throw new AssertionError("impact analysis must preserve the expected 64-node chain");
    }
    return rows.stream().map(value -> value.withNodeCount(nodeCount)).toList();
  }

  private static Row benchmarkBoundedScenario(
      Path project,
      String scenario,
      int filesAvailable,
      int expectedCurrentDefinitions,
      boolean currentRun)
      throws Exception {
    ProjectFiles files = new ProjectFiles(project);
    HopProjectDefinitionIndex index = newIndex(files);
    Long beforeRefreshes = refreshCount(index);
    long started = System.nanoTime();
    HopProjectDefinitionIndex.Snapshot snapshot = index.snapshot();
    long duration = System.nanoTime() - started;
    Long afterRefreshes = refreshCount(index);
    assertDefinitionPaths(project, snapshot, scenario.startsWith("mixed-") && currentRun);

    if (scenario.startsWith("mixed-")) {
      if (currentRun) {
        if (snapshot.definitions().size() != expectedCurrentDefinitions
            || snapshot.truncated()
            || filesExamined(snapshot, files) != filesAvailable) {
          throw new AssertionError(
              "current mixed-project scan must index all definitions without truncation");
        }
      } else if (snapshot.definitions().size() > 5_000
          || !snapshot.truncated()
          || filesExamined(snapshot, files) > ProjectFiles.MAX_SCAN_FILES) {
        throw new AssertionError("legacy mixed-project scan must report its regular-file bound");
      }
    } else if (snapshot.definitions().size() != expectedCurrentDefinitions
        || !snapshot.truncated()) {
      throw new AssertionError("definition result limit must be reported as truncated");
    }

    return row(
        scenario,
        "cold_bounds",
        duration,
        snapshot,
        files,
        filesAvailable,
        delta(beforeRefreshes, afterRefreshes),
        null);
  }

  private static Row row(
      String scenario,
      String phase,
      long durationNanos,
      HopProjectDefinitionIndex.Snapshot snapshot,
      ProjectFiles files,
      int filesAvailable,
      Long refreshes,
      Integer nodeCount)
      throws Exception {
    return new Row(
        scenario,
        phase,
        durationNanos / 1_000_000.0,
        snapshot.definitions().size(),
        filesAvailable,
        filesExamined(snapshot, files),
        snapshot.cacheHits(),
        snapshot.cacheMisses(),
        refreshes,
        snapshot.truncated(),
        nodeCount);
  }

  private static int filesExamined(HopProjectDefinitionIndex.Snapshot snapshot, ProjectFiles files)
      throws Exception {
    Number count = numberAccessor(snapshot, "regularFilesExamined", "scannedFiles");
    if (count != null) return count.intValue();
    Object scan = files.definitionScan(ProjectFiles.MAX_SCAN_FILES);
    count = numberAccessor(scan, "regularFilesExamined", "scannedFiles");
    return count == null
        ? files.definitionScan(ProjectFiles.MAX_SCAN_FILES).files().size()
        : count.intValue();
  }

  private static Long refreshCount(HopProjectDefinitionIndex index) throws Exception {
    try {
      Method metricsMethod = index.getClass().getDeclaredMethod("metrics");
      Object value = metricsMethod.invoke(index);
      if (value instanceof Map<?, ?> metrics && metrics.get("refreshes") instanceof Number number) {
        return number.longValue();
      }
    } catch (NoSuchMethodException ignored) {
      // The 2.2.1 index does not publish refresh metrics.
    }
    return null;
  }

  private static HopProjectDefinitionIndex newIndex(ProjectFiles files) throws Exception {
    try {
      return HopProjectDefinitionIndex.class
          .getDeclaredConstructor(ProjectFiles.class, IHopMetadataProvider.class, IVariables.class)
          .newInstance(files, new MemoryMetadataProvider(), new Variables());
    } catch (NoSuchMethodException baselineHasNoTypedExtractor) {
      return new HopProjectDefinitionIndex(files);
    }
  }

  private static Number numberAccessor(Object value, String... names) throws Exception {
    for (String name : names) {
      try {
        Object result = value.getClass().getDeclaredMethod(name).invoke(value);
        if (result instanceof Number number) return number;
      } catch (NoSuchMethodException ignored) {
        // Try the accessor name from the other benchmarked version.
      }
    }
    return null;
  }

  private static Long delta(Long before, Long after) {
    return before == null || after == null ? null : after - before;
  }

  private static void assertIndex(
      HopProjectDefinitionIndex.Snapshot snapshot,
      int expectedDefinitions,
      boolean expectedTruncated,
      String phase) {
    if (snapshot.definitions().size() != expectedDefinitions
        || snapshot.truncated() != expectedTruncated) {
      throw new AssertionError(
          phase
              + " mismatch: definitions="
              + snapshot.definitions().size()
              + ", truncated="
              + snapshot.truncated());
    }
  }

  private static void assertDefinitionPaths(
      Path project, HopProjectDefinitionIndex.Snapshot snapshot, boolean expectComplete)
      throws Exception {
    Set<String> available;
    try (var paths = Files.walk(project)) {
      available =
          paths
              .filter(Files::isRegularFile)
              .filter(HopProjectDefinitionBenchmark::isDefinition)
              .map(project::relativize)
              .map(Path::toString)
              .map(path -> path.replace('\\', '/'))
              .collect(Collectors.toSet());
    }
    Set<String> indexed =
        snapshot.definitions().keySet().stream()
            .map(path -> path.replace('\\', '/'))
            .collect(Collectors.toSet());
    if (!available.containsAll(indexed)) {
      throw new AssertionError("index returned a path absent from the fixture definition set");
    }
    if (expectComplete && !indexed.equals(available)) {
      throw new AssertionError(
          "index output paths differ from the complete fixture definition set");
    }
  }

  private static boolean isDefinition(Path path) {
    String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
    return name.endsWith(".hpl") || name.endsWith(".hwf");
  }

  private static Path coreProject(Path fixtureRoot, String name, int definitions) throws Exception {
    Path project = fixtureRoot.resolve(name);
    Path marker = fixtureRoot.resolve(name + ".ready");
    if (Files.exists(marker)) return project;
    Files.createDirectories(project);
    Files.writeString(project.resolve("target.hpl"), pipeline("select * from DWH.DIM_SITE", ""));
    String previous = "target.hpl";
    for (int i = 1; i <= CHAIN_LENGTH; i++) {
      String current = String.format("chain-%05d.hwf", i);
      Files.writeString(project.resolve(current), workflow(previous));
      previous = current;
    }
    for (int i = CHAIN_LENGTH + 1; i < definitions; i++) {
      Files.writeString(
          project.resolve(String.format("filler-%05d.hpl", i)),
          pipeline("select * from DWH.UNRELATED_" + i, ""));
    }
    Files.writeString(marker, "ready");
    return project;
  }

  private static Path mixedProject(Path fixtureRoot, int files, int definitions) throws Exception {
    String name = "mixed-" + files + "-" + definitions;
    Path project = fixtureRoot.resolve(name);
    Path marker = fixtureRoot.resolve(name + ".ready");
    if (Files.exists(marker)) return project;
    Path noise = Files.createDirectories(project.resolve("a-noise"));
    Path definitionsDirectory = Files.createDirectories(project.resolve("z-definitions"));
    int noiseCount = files - definitions;
    for (int i = 0; i < noiseCount; i++) {
      Files.writeString(noise.resolve(String.format("noise-%05d.txt", i)), "bounded noise\n");
    }
    for (int i = 0; i < definitions; i++) {
      Files.writeString(
          definitionsDirectory.resolve(String.format("definition-%05d.hpl", i)),
          pipeline("select * from DWH.UNRELATED_" + i, ""));
    }
    Files.writeString(marker, "ready");
    return project;
  }

  private static Path definitionsOnlyProject(Path fixtureRoot, String name, int definitions)
      throws Exception {
    Path project = fixtureRoot.resolve(name);
    Path marker = fixtureRoot.resolve(name + ".ready");
    if (Files.exists(marker)) return project;
    Files.createDirectories(project);
    for (int i = 0; i < definitions; i++) {
      Files.writeString(
          project.resolve(String.format("definition-%05d.hpl", i)),
          pipeline("select * from DWH.UNRELATED_" + i, ""));
    }
    Files.writeString(marker, "ready");
    return project;
  }

  private static byte[] appendSpace(byte[] original) {
    byte[] changed = java.util.Arrays.copyOf(original, original.length + 1);
    changed[changed.length - 1] = ' ';
    return changed;
  }

  private static String pipeline(String sql, String note) {
    return "<pipeline><transform><name>Table Input</name><type>TableInput</type><sql>"
        + sql
        + "</sql></transform><transform><name>Filter Rows</name><type>FilterRows</type></transform>"
        + "<hop><from>Table Input</from><to>Filter Rows</to></hop>"
        + (note.isBlank() ? "" : "<note>" + note + "</note>")
        + "</pipeline>";
  }

  private static String workflow(String reference) {
    return "<workflow><action><name>Run pipeline</name><type>Pipeline</type></action>"
        + "<note>"
        + reference
        + "</note></workflow>";
  }

  private static String json(String mode, List<Row> rows) {
    StringBuilder output =
        new StringBuilder("{\n  \"mode\": \"")
            .append(escape(mode))
            .append("\",\n  \"java_version\": \"")
            .append(escape(System.getProperty("java.version")))
            .append("\",\n  \"available_processors\": ")
            .append(Runtime.getRuntime().availableProcessors())
            .append(",\n  \"max_heap_bytes\": ")
            .append(Runtime.getRuntime().maxMemory())
            .append(",\n  \"rows\": [\n");
    for (int i = 0; i < rows.size(); i++) {
      Row row = rows.get(i);
      output
          .append("    {\"scenario\":\"")
          .append(escape(row.scenario()))
          .append("\"")
          .append(",\"phase\":\"")
          .append(row.phase())
          .append("\"")
          .append(",\"duration_ms\":")
          .append(String.format(java.util.Locale.ROOT, "%.3f", row.durationMs()))
          .append(",\"definitions\":")
          .append(row.definitions())
          .append(",\"files_available\":")
          .append(row.filesAvailable())
          .append(",\"files_examined\":")
          .append(row.filesExamined())
          .append(",\"cache_hits\":")
          .append(row.cacheHits())
          .append(",\"cache_misses\":")
          .append(row.cacheMisses())
          .append(",\"refreshes\":")
          .append(nullable(row.refreshes()))
          .append(",\"truncated\":")
          .append(row.truncated())
          .append(",\"node_count\":")
          .append(nullable(row.nodeCount()))
          .append("}")
          .append(i + 1 == rows.size() ? "\n" : ",\n");
    }
    return output.append("  ]\n}\n").toString();
  }

  private static String nullable(Number value) {
    return value == null ? "null" : value.toString();
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private record Row(
      String scenario,
      String phase,
      double durationMs,
      int definitions,
      int filesAvailable,
      int filesExamined,
      int cacheHits,
      int cacheMisses,
      Long refreshes,
      boolean truncated,
      Integer nodeCount) {
    private Row withNodeCount(int count) {
      return new Row(
          scenario,
          phase,
          durationMs,
          definitions,
          filesAvailable,
          filesExamined,
          cacheHits,
          cacheMisses,
          refreshes,
          truncated,
          count);
    }
  }
}
