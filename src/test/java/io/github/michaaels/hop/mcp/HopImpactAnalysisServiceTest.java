package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HopImpactAnalysisServiceTest {
  @TempDir Path project;

  @Test
  void combinesTablesReferencesDependenciesAndLineageWithinBounds() throws Exception {
    Files.writeString(
        project.resolve("enrich_cells.hpl"),
        pipeline("select * from DWH.DIM_SITE", "child.hwf", "DWH_PROD"));
    Files.writeString(
        project.resolve("load_site.hpl"), pipeline("insert into DWH.DIM_SITE", "", "DWH_PROD"));
    Files.writeString(
        project.resolve("parent.hwf"), workflow("enrich_cells.hpl", "DWH_PROD"));
    Files.writeString(project.resolve("child.hwf"), workflow("", "DWH_PROD"));
    Files.writeString(
        project.resolve("archive.hpl"),
        pipeline("select * from DWH.DIM_SITE_ARCHIVE", "", "DWH_PROD"));

    Map<String, Object> result =
        new HopImpactAnalysisService(new ProjectFiles(project))
            .analyze("DWH.DIM_SITE", null, null, 10, 20, 20);

    assertEquals(3, result.get("node_count"));
    assertEquals(3, result.get("returned_nodes"));
    assertTrue(String.valueOf(result.get("table_references")).contains("DWH.DIM_SITE"));
    assertTrue(String.valueOf(result.get("dependencies")).contains("parent.hwf"));
    assertTrue(String.valueOf(result.get("nodes")).contains("parent.hwf"));
    assertTrue(!String.valueOf(result.get("nodes")).contains("child.hwf"));
    assertTrue(!String.valueOf(result.get("nodes")).contains("archive.hpl"));
    assertTrue(String.valueOf(result.get("lineage")).contains("enrich_cells.hpl"));
    assertEquals(false, result.get("results_truncated"));
  }

  @Test
  void supportsMetadataAndDefinitionSelectorsAndRejectsUnsafeInputs() throws Exception {
    Files.writeString(
        project.resolve("load.hpl"), pipeline("select * from DWH.DIM_SITE", "child.hwf", "DWH_PROD"));
    Files.writeString(project.resolve("child.hwf"), workflow("", "DWH_PROD"));
    HopImpactAnalysisService service = new HopImpactAnalysisService(new ProjectFiles(project));

    Map<String, Object> metadata = service.analyze(null, "DWH_PROD", null, 5, 10, 10);
    assertEquals(2, metadata.get("node_count"));
    assertTrue(String.valueOf(metadata.get("metadata_references")).contains("DWH_PROD"));

    Map<String, Object> definition = service.analyze(null, null, "child.hwf", 5, 10, 10);
    assertEquals(2, definition.get("node_count"));
    assertTrue(String.valueOf(definition.get("nodes")).contains("load.hpl"));

    assertThrows(
        IllegalArgumentException.class, () -> service.analyze("A", "B", null, 5, 10, 10));
    assertThrows(
        Exception.class, () -> service.analyze(null, null, "../outside.hpl", 5, 10, 10));
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
}
