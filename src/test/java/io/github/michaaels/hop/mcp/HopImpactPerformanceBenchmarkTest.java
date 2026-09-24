package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfSystemProperty(named = "hop.benchmark", matches = "true")
class HopImpactPerformanceBenchmarkTest {
  @TempDir Path root;

  @Test
  void benchmarkColdWarmAndIncrementalImpactAnalysis() throws Exception {
    System.out.printf(
        "BENCHMARK_ENV java=%s cores=%d max_heap_mb=%d%n",
        System.getProperty("java.version"),
        Runtime.getRuntime().availableProcessors(),
        Runtime.getRuntime().maxMemory() / (1024L * 1024L));

    Path warmup = root.resolve("warmup");
    generateProject(warmup, 100);
    HopImpactAnalysisService warmupService =
        new HopImpactAnalysisService(new ProjectFiles(warmup));
    warmupService.analyze("DWH.DIM_SITE", null, null, 64, 500, 200);
    warmupService.analyze("DWH.DIM_SITE", null, null, 64, 500, 200);

    for (int size : new int[] {1000, 2500, 5000}) {
      Path project = root.resolve("project-" + size);
      long generateStart = System.nanoTime();
      Path changedFile = generateProject(project, size);
      long generateMs = elapsedMs(generateStart);

      HopImpactAnalysisService service =
          new HopImpactAnalysisService(new ProjectFiles(project));

      long coldStart = System.nanoTime();
      Map<String, Object> cold =
          service.analyze("DWH.DIM_SITE", null, null, 64, 500, 200);
      long coldMs = elapsedMs(coldStart);

      long[] warm = new long[3];
      for (int i = 0; i < warm.length; i++) {
        long start = System.nanoTime();
        service.analyze("DWH.DIM_SITE", null, null, 64, 500, 200);
        warm[i] = elapsedMs(start);
      }
      Arrays.sort(warm);
      long warmMedianMs = warm[1];

      Files.writeString(
          changedFile,
          pipeline("select * from DWH.UNRELATED_CHANGED", "", "OTHER_CHANGED"));

      long incrementalStart = System.nanoTime();
      Map<String, Object> incremental =
          service.analyze("DWH.DIM_SITE", null, null, 64, 500, 200);
      long incrementalMs = elapsedMs(incrementalStart);

      int nodeCount = ((Number) cold.get("node_count")).intValue();
      int incrementalNodeCount = ((Number) incremental.get("node_count")).intValue();
      assertTrue(nodeCount >= 2, "expected an affected dependency chain");
      assertTrue(incrementalNodeCount >= 2, "incremental result must preserve the chain");

      System.out.printf(
          "BENCHMARK_RESULT size=%d generate_ms=%d cold_ms=%d warm_runs_ms=%s warm_median_ms=%d incremental_ms=%d cold_to_warm_x=%.2f cold_to_incremental_x=%.2f node_count=%d%n",
          size,
          generateMs,
          coldMs,
          Arrays.toString(warm),
          warmMedianMs,
          incrementalMs,
          ratio(coldMs, warmMedianMs),
          ratio(coldMs, incrementalMs),
          nodeCount);
    }
  }

  private static Path generateProject(Path project, int size) throws Exception {
    Files.createDirectories(project);
    Files.writeString(
        project.resolve("target.hpl"),
        pipeline("select * from DWH.DIM_SITE", "", "DWH_PROD"));

    int chainCount = Math.min(63, size - 1);
    String previous = "target.hpl";
    for (int i = 1; i <= chainCount; i++) {
      String current = String.format("chain-%05d.hwf", i);
      Files.writeString(project.resolve(current), workflow(previous, "DWH_PROD"));
      previous = current;
    }

    int created = 1 + chainCount;
    Path changedFile = project.resolve("target.hpl");
    for (int i = created; i < size; i++) {
      Path filler = project.resolve(String.format("filler-%05d.hpl", i));
      Files.writeString(
          filler,
          pipeline("select * from DWH.UNRELATED_" + i, "", "OTHER_" + i));
      changedFile = filler;
    }
    return changedFile;
  }

  private static String pipeline(String sql, String reference, String metadata) {
    return "<pipeline><transform><name>Table Input</name><type>TableInput</type><sql>"
        + sql
        + "</sql><connection>"
        + metadata
        + "</connection></transform><transform><name>Filter Rows</name><type>FilterRows</type></transform>"
        + "<hop><from>Table Input</from><to>Filter Rows</to></hop>"
        + (reference.isBlank() ? "" : "<note>" + reference + "</note>")
        + "</pipeline>";
  }

  private static String workflow(String reference, String metadata) {
    return "<workflow><action><name>Run pipeline</name><type>Pipeline</type><connection>"
        + metadata
        + "</connection></action><hop><from>Run pipeline</from><to>Run pipeline</to></hop><note>"
        + reference
        + "</note></workflow>";
  }

  private static long elapsedMs(long startedNanos) {
    return Math.max(1L, (System.nanoTime() - startedNanos) / 1_000_000L);
  }

  private static double ratio(long numerator, long denominator) {
    return denominator <= 0 ? 0.0 : (double) numerator / (double) denominator;
  }
}
