package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class HopGuidedPlanTest {
  @TempDir Path project;
  private final List<HopGuidedPresentation> packets = new ArrayList<>();
  private final HopSemanticEventSink sink =
      new HopSemanticEventSink() {
        public boolean publish(Event event) {
          return true;
        }

        public boolean publishPresentation(HopGuidedPresentation packet) {
          packets.add(packet);
          return true;
        }
      };

  @BeforeAll
  static void init() throws Exception {
    HopEnvironment.init();
  }

  private HopCorrectionPlanManager manager() throws Exception {
    return new HopCorrectionPlanManager(
        new HopDefinitionMutator(
            new ProjectFiles(project), new Variables(), new MemoryMetadataProvider(), sink),
        sink);
  }

  private static String id(Map<String, Object> plan) {
    return (String) plan.get("plan_id");
  }

  private static List<Map<String, Object>> create() {
    return List.of(
        Map.of("operation", "add_component", "name", "A", "plugin_id", "Dummy"),
        Map.of("operation", "add_component", "name", "B", "plugin_id", "Dummy"),
        Map.of("operation", "add_hop", "from", "A", "to", "B"),
        Map.of("operation", "auto_layout"));
  }

  @Test
  void beginsThenAppendsImmutableBlocksWithoutWritingOrWaitingForGuiPlayback() throws Exception {
    var manager = manager();
    var first = manager.prepare("guided.hpl", "pipeline", List.of(), "guided", null);
    assertEquals(0, packets.getFirst().steps().size());
    assertFalse(Files.exists(project.resolve("guided.hpl")));
    var next = manager.prepare("guided.hpl", "pipeline", create(), null, id(first));
    assertEquals("superseded", manager.status(id(first)).get("state"));
    assertEquals(packets.getFirst().id(), packets.getLast().id());
    assertEquals(2, packets.getLast().revision());
    assertFalse(Files.exists(project.resolve("guided.hpl")));
    assertThrows(
        McpException.class, () -> manager.apply(id(first), (String) first.get("plan_sha256")));
    var applied = manager.apply(id(next), (String) next.get("plan_sha256"), "instant");
    assertEquals("applied", applied.get("state"));
    assertTrue(Files.exists(project.resolve("guided.hpl")));
    assertEquals("instant", packets.getLast().mode());
    assertEquals("applied", packets.getLast().state());
  }

  @Test
  void instantPreparationPreservesLegacyBehaviorAndCreatesNoVisualQueue() throws Exception {
    var manager = manager();
    var plan = manager.prepare("instant.hpl", "pipeline", create(), "instant", null);
    assertTrue(packets.isEmpty());
    assertFalse(Files.exists(project.resolve("instant.hpl")));
    manager.apply(id(plan), (String) plan.get("plan_sha256"));
    assertTrue(packets.isEmpty());
  }

  @Test
  void cancellationPreventsLaterApplicationAndNeverWritesTheDraft() throws Exception {
    var manager = manager();
    var plan = manager.prepare("cancel.hpl", "pipeline", create(), "guided", null);
    assertEquals("cancelled", manager.status(id(plan), true).get("state"));
    assertEquals("cancelled", packets.getLast().state());
    assertThrows(
        McpException.class, () -> manager.apply(id(plan), (String) plan.get("plan_sha256")));
    assertFalse(Files.exists(project.resolve("cancel.hpl")));
  }

  @Test
  void conflictingExternalEditRejectsTheNextRevisionAndApplyKeepsShaProtection() throws Exception {
    var manager = manager();
    var initial = manager.prepare("stale.hpl", "pipeline", create());
    manager.apply(id(initial), (String) initial.get("plan_sha256"));
    var plan = manager.prepare("stale.hpl", "pipeline", List.of(), "guided", null);
    Files.writeString(project.resolve("stale.hpl"), "\n", StandardOpenOption.APPEND);
    byte[] external = Files.readAllBytes(project.resolve("stale.hpl"));
    assertThrows(
        McpException.class,
        () -> manager.prepare("stale.hpl", "pipeline", List.of(), null, id(plan)));
    assertThrows(Exception.class, () -> manager.apply(id(plan), (String) plan.get("plan_sha256")));
    assertArrayEquals(external, Files.readAllBytes(project.resolve("stale.hpl")));
    assertEquals("failed", packets.getLast().state());
  }

  @Test
  void blockAppendCannotChangeDefinitionOrBypassOperationBounds() throws Exception {
    var manager = manager();
    var plan = manager.prepare("bound.hpl", "pipeline", List.of(), "guided", null);
    assertThrows(
        IllegalArgumentException.class,
        () -> manager.prepare("other.hpl", "pipeline", create(), null, id(plan)));
    var tooMany =
        Collections.<Map<String, Object>>nCopies(51, Map.of("operation", "set_name", "value", "x"));
    assertThrows(
        IllegalArgumentException.class,
        () -> manager.prepare("bound.hpl", "pipeline", tooMany, null, id(plan)));
    assertEquals("prepared", manager.status(id(plan)).get("state"));
    assertThrows(
        IllegalArgumentException.class,
        () -> manager.prepare("invalid.hpl", "pipeline", create(), "slow", null));
  }
}
