package io.github.michaaels.hop.mcp;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** Performs project scans with hard bounds on visited entries, files and depth. */
final class BoundedProjectWalker {
  static final int MAX_VISITED_ENTRIES = 50_000;
  static final int MAX_FILES_SCANNED = 5_000;
  static final int MAX_DEPTH = 64;

  record ScanResult(
      List<Path> files,
      int visitedEntries,
      int scannedFiles,
      boolean scanLimitReached,
      boolean resultsTruncated) {}

  private BoundedProjectWalker() {}

  static ScanResult scan(
      Path root, Path internalDirectory, Predicate<Path> include, int resultLimit)
      throws IOException {
    return scan(
        root,
        internalDirectory,
        include,
        resultLimit,
        MAX_VISITED_ENTRIES,
        MAX_FILES_SCANNED,
        MAX_DEPTH);
  }

  static ScanResult scan(
      Path root,
      Path internalDirectory,
      Predicate<Path> include,
      int resultLimit,
      int maxVisitedEntries,
      int maxFilesScanned,
      int maxDepth)
      throws IOException {
    Objects.requireNonNull(root);
    Objects.requireNonNull(include);
    if (maxVisitedEntries < 1 || maxFilesScanned < 1 || maxDepth < 1)
      throw new IllegalArgumentException("walker limits must be positive");
    if (resultLimit < 1 || resultLimit > maxFilesScanned)
      throw new IllegalArgumentException("resultLimit must be between 1 and " + maxFilesScanned);

    List<Path> files = new ArrayList<>();
    int[] visited = {0};
    int[] scanned = {0};
    boolean[] scanLimitReached = {false};
    boolean[] resultsTruncated = {false};
    Path normalizedInternal =
        internalDirectory == null ? null : internalDirectory.toAbsolutePath().normalize();

    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          private FileVisitResult countEntry() {
            if (++visited[0] > maxVisitedEntries) {
              visited[0]--;
              scanLimitReached[0] = true;
              return FileVisitResult.TERMINATE;
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
            FileVisitResult counted = countEntry();
            if (counted == FileVisitResult.TERMINATE) return counted;
            if (normalizedInternal != null
                && directory.toAbsolutePath().normalize().startsWith(normalizedInternal))
              return FileVisitResult.SKIP_SUBTREE;
            if (root.relativize(directory).getNameCount() > maxDepth) {
              scanLimitReached[0] = true;
              return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            FileVisitResult counted = countEntry();
            if (counted == FileVisitResult.TERMINATE) return counted;
            if (root.relativize(file).getNameCount() > maxDepth) {
              scanLimitReached[0] = true;
              return FileVisitResult.CONTINUE;
            }
            if (!attrs.isRegularFile() || Files.isSymbolicLink(file))
              return FileVisitResult.CONTINUE;
            if (++scanned[0] > maxFilesScanned) {
              scanned[0]--;
              scanLimitReached[0] = true;
              return FileVisitResult.TERMINATE;
            }
            if (!include.test(file)) return FileVisitResult.CONTINUE;
            if (files.size() == resultLimit) {
              resultsTruncated[0] = true;
              return FileVisitResult.TERMINATE;
            }
            files.add(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exception) {
            FileVisitResult counted = countEntry();
            return counted == FileVisitResult.TERMINATE ? counted : FileVisitResult.CONTINUE;
          }
        });

    files.sort(Comparator.comparing(path -> root.relativize(path).toString().replace('\\', '/')));
    return new ScanResult(
        List.copyOf(files), visited[0], scanned[0], scanLimitReached[0], resultsTruncated[0]);
  }
}
