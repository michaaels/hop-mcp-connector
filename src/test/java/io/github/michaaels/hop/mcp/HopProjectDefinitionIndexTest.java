package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HopProjectDefinitionIndexTest {
  @TempDir Path project;

  @Test
  void reusesUnchangedDefinitionsAndReindexesOnlyChangedFiles() throws Exception {
    Path first = project.resolve("first.hpl");
    Path second = project.resolve("second.hwf");
    Files.writeString(first, pipeline("A"));
    Files.writeString(second, workflow("B"));

    HopProjectDefinitionIndex index = new HopProjectDefinitionIndex(new ProjectFiles(project));

    HopProjectDefinitionIndex.Snapshot initial = index.snapshot();
    assertEquals(2, initial.definitions().size());
    assertEquals(0, initial.cacheHits());
    assertEquals(2, initial.cacheMisses());

    HopProjectDefinitionIndex.Snapshot reused = index.snapshot();
    assertEquals(2, reused.cacheHits());
    assertEquals(0, reused.cacheMisses());

    Thread.sleep(5L);
    Files.writeString(first, pipeline("A_CHANGED"));

    HopProjectDefinitionIndex.Snapshot changed = index.snapshot();
    assertEquals(1, changed.cacheHits());
    assertEquals(1, changed.cacheMisses());
    assertTrue(changed.definitions().get("first.hpl").containsText("A_CHANGED"));
  }

  private static String pipeline(String value) {
    return "<pipeline><transform><name>Input</name><type>Dummy</type><connection>"
        + value
        + "</connection></transform></pipeline>";
  }

  private static String workflow(String value) {
    return "<workflow><action><name>Run</name><type>Dummy</type><connection>"
        + value
        + "</connection></action></workflow>";
  }
}
