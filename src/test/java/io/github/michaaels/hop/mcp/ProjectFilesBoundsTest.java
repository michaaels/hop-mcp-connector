package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectFilesBoundsTest {
  @TempDir Path temp;

  @Test
  void definitionsAndCatalogPagesAreStableAndExposeCompleteness() throws Exception {
    Path root = Files.createDirectory(temp.resolve("project"));
    write(root, "z.hpl", "pipeline");
    write(root, "flows/a.hwf", "workflow");
    write(root, "notes/readme.txt", "notes");
    write(root, ".hop-mcp/backups/hidden.hpl", "private");
    ProjectFiles files = new ProjectFiles(root);

    Map<String, Object> definitionsFirst = files.definitionsPage(0, 1);
    Map<String, Object> definitionsFirstAgain = files.definitionsPage(0, 1);
    Map<String, Object> definitionsLast = files.definitionsPage(1, 1);
    assertEquals(definitionsFirst.get("definitions"), definitionsFirstAgain.get("definitions"));
    assertEquals("flows/a.hwf", firstPath(definitionsFirst, "definitions"));
    assertEquals("z.hpl", firstPath(definitionsLast, "definitions"));
    assertEquals(2, definitionsFirst.get("count"));
    assertEquals(1, definitionsFirst.get("returned"));
    assertEquals(true, definitionsFirst.get("count_complete"));
    assertEquals(false, definitionsFirst.get("scan_limit_reached"));
    assertEquals(true, definitionsFirst.get("results_truncated"));
    assertEquals(true, definitionsFirst.get("has_more"));
    assertEquals(false, definitionsLast.get("has_more"));
    assertEquals(false, definitionsLast.get("results_truncated"));
    assertTrue((Integer) definitionsFirst.get("visited") > 0);
    assertEquals("workflow", firstValue(definitionsFirst, "definitions", "type"));

    Map<String, Object> catalogFirst = files.catalog("**", 0, 2);
    Map<String, Object> catalogLast = files.catalog("**", 2, 2);
    assertEquals(List.of("flows/a.hwf", "notes/readme.txt"), paths(catalogFirst, "files"));
    assertEquals(List.of("z.hpl"), paths(catalogLast, "files"));
    assertEquals(3, catalogFirst.get("count"));
    assertEquals(2, catalogFirst.get("returned"));
    assertEquals(true, catalogFirst.get("count_complete"));
    assertEquals(false, catalogFirst.get("scan_limit_reached"));
    assertEquals(true, catalogFirst.get("results_truncated"));
    assertEquals(true, catalogFirst.get("has_more"));
    assertEquals(false, catalogLast.get("has_more"));
    assertTrue(((String) firstValue(catalogFirst, "files", "sha256")).matches("[0-9a-f]{64}"));
  }

  @Test
  void walkerStopsAtVisitedFileAndDepthLimits() throws Exception {
    Path visitedRoot = Files.createDirectory(temp.resolve("visited"));
    write(visitedRoot, "a.txt", "a");
    write(visitedRoot, "b.txt", "b");
    BoundedProjectWalker.ScanResult visitedLimited =
        BoundedProjectWalker.scan(visitedRoot, null, path -> true, 10, 1, 10, 64);
    assertEquals(1, visitedLimited.visitedEntries());
    assertTrue(visitedLimited.scanLimitReached());
    assertTrue(visitedLimited.files().isEmpty());

    BoundedProjectWalker.ScanResult fileLimited =
        BoundedProjectWalker.scan(visitedRoot, null, path -> true, 1, 10, 1, 64);
    assertEquals(1, fileLimited.scannedFiles());
    assertEquals(1, fileLimited.files().size());
    assertTrue(fileLimited.scanLimitReached());

    Path depthRoot = Files.createDirectory(temp.resolve("depth"));
    write(depthRoot, "shallow.txt", "shallow");
    write(depthRoot, "sub/deeper.txt", "deeper");
    BoundedProjectWalker.ScanResult depthLimited =
        BoundedProjectWalker.scan(depthRoot, null, path -> true, 10, 20, 10, 1);
    assertEquals(List.of("shallow.txt"), relativePaths(depthRoot, depthLimited.files()));
    assertTrue(depthLimited.scanLimitReached());
  }

  @Test
  void walkerDoesNotFollowSymbolicLinks() throws Exception {
    Path root = Files.createDirectory(temp.resolve("project"));
    Path outside = Files.writeString(temp.resolve("outside.txt"), "outside");
    Path link = root.resolve("escape.txt");
    try {
      Files.createSymbolicLink(link, outside);
    } catch (IOException | UnsupportedOperationException | SecurityException e) {
      Assumptions.assumeTrue(false, "symbolic links are unavailable in this environment");
      return;
    }

    BoundedProjectWalker.ScanResult scan = BoundedProjectWalker.scan(root, null, path -> true, 10);
    assertEquals(0, scan.scannedFiles());
    assertTrue(scan.files().isEmpty());
    assertFalse(scan.scanLimitReached());
  }

  @Test
  void textChunksKeepUtf8CodePointsWholeAndRejectInteriorOffsets() throws Exception {
    ProjectFiles files = new ProjectFiles(temp);
    String text = "A\uD83D\uDE00B\u00E9C";

    Map<String, Object> first = files.textChunk("unicode.txt", text, 0, 4);
    Map<String, Object> second = files.textChunk("unicode.txt", text, 1, 4);
    Map<String, Object> third = files.textChunk("unicode.txt", text, 5, 4);
    assertEquals("A", first.get("text"));
    assertEquals(1, first.get("returned_bytes"));
    assertEquals(true, first.get("truncated"));
    assertEquals("\uD83D\uDE00", second.get("text"));
    assertEquals(4, second.get("returned_bytes"));
    assertEquals(5L, second.get("next_offset"));
    assertEquals("B\u00E9C", third.get("text"));
    assertEquals(4, third.get("returned_bytes"));
    assertEquals(true, third.get("eof"));
    assertThrows(IllegalArgumentException.class, () -> files.textChunk("unicode.txt", text, 2, 4));
    assertThrows(IllegalArgumentException.class, () -> files.textChunk("unicode.txt", text, 10, 4));
    assertThrows(IllegalArgumentException.class, () -> files.textChunk("unicode.txt", text, -1, 4));
  }

  @Test
  void searchSkipsMalformedUtf8AndSignalsAnIncompleteScan() throws Exception {
    Path root = Files.createDirectory(temp.resolve("project"));
    Files.write(root.resolve("a-invalid.txt"), new byte[] {(byte) 0xc3, (byte) 0x28});
    Files.writeString(root.resolve("b-match.txt"), "Needle is here\n");

    Map<String, Object> result = new ProjectFiles(root).search("needle", "**", 0, 10);
    assertEquals(1, result.get("count"));
    assertEquals(1, result.get("returned"));
    assertEquals(2, result.get("scanned_files"));
    assertEquals(false, result.get("count_complete"));
    assertEquals(true, result.get("scan_limit_reached"));
    assertEquals(true, result.get("results_truncated"));
    assertEquals(true, result.get("has_more"));
    assertEquals(List.of("b-match.txt"), paths(result, "results"));
  }

  @Test
  void catalogAndSearchStayWithinAggregateByteBudget() throws Exception {
    Path root = Files.createDirectory(temp.resolve("large-project"));
    byte[] contents = new byte[Math.toIntExact(ProjectFiles.MAX_FILE_BYTES)];
    Arrays.fill(contents, (byte) 'x');
    int fileCount = (int) (ProjectFiles.MAX_TOTAL_SCAN_BYTES / ProjectFiles.MAX_FILE_BYTES) + 1;
    for (int index = 0; index < fileCount; index++) {
      Files.write(root.resolve("file-" + index + ".txt"), contents);
    }
    ProjectFiles files = new ProjectFiles(root);

    Map<String, Object> search = files.search("needle", "**", 0, 10);
    assertEquals(ProjectFiles.MAX_TOTAL_SCAN_BYTES, search.get("scanned_bytes"));
    assertEquals(true, search.get("scan_limit_reached"));

    Map<String, Object> catalog = files.catalog("**", 0, 10);
    assertEquals(ProjectFiles.MAX_TOTAL_SCAN_BYTES, catalog.get("scanned_bytes"));
    assertEquals(true, catalog.get("scan_limit_reached"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> entries = (List<Map<String, Object>>) catalog.get("files");
    assertTrue(entries.stream().anyMatch(entry -> Boolean.TRUE.equals(entry.get("hash_skipped"))));
  }

  @Test
  void resolveRejectsEscapesAndConnectorInternalFiles() throws Exception {
    Path root = Files.createDirectory(temp.resolve("project"));
    write(root, ".hop-mcp/backups/hidden.txt", "private");
    ProjectFiles files = new ProjectFiles(root);

    McpException escape = assertThrows(McpException.class, () -> files.resolve("../outside.txt"));
    assertEquals("PATH_OUTSIDE_PROJECT", escape.code());
    McpException internal =
        assertThrows(McpException.class, () -> files.resolve(".hop-mcp/backups/hidden.txt"));
    assertEquals("INTERNAL_PATH_DENIED", internal.code());
    McpException internalWrite =
        assertThrows(
            McpException.class, () -> files.resolveForWrite(".hop-mcp/backups/hidden.txt"));
    assertEquals("INTERNAL_PATH_DENIED", internalWrite.code());
  }

  private static void write(Path root, String relative, String content) throws IOException {
    Path path = root.resolve(relative);
    Files.createDirectories(path.getParent());
    Files.writeString(path, content);
  }

  @SuppressWarnings("unchecked")
  private static List<String> paths(Map<String, Object> result, String field) {
    return ((List<Map<String, Object>>) result.get(field))
        .stream().map(item -> (String) item.get("path")).toList();
  }

  @SuppressWarnings("unchecked")
  private static String firstPath(Map<String, Object> result, String field) {
    return (String) ((List<Map<String, Object>>) result.get(field)).getFirst().get("path");
  }

  @SuppressWarnings("unchecked")
  private static Object firstValue(Map<String, Object> result, String field, String key) {
    return ((List<Map<String, Object>>) result.get(field)).getFirst().get(key);
  }

  private static List<String> relativePaths(Path root, List<Path> paths) {
    return paths.stream().map(path -> root.relativize(path).toString().replace('\\', '/')).toList();
  }
}
