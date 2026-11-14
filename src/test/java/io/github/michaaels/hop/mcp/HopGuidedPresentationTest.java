package io.github.michaaels.hop.mcp;

import static io.github.michaaels.hop.mcp.HopGuidedPresentation.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HopGuidedPresentationTest {
  @TempDir Path project;
  private final String id = UUID.randomUUID().toString();
  private static final Graph EMPTY = new Graph(List.of(), List.of());
  private static final Graph RESULT =
      new Graph(
          List.of(new Node("A", "Dummy", 80, 80), new Node("B", "Dummy", 280, 80)),
          List.of(new Edge("A", "B", true, 0)));
  private static final List<Step> STEPS =
      List.of(
          new Step("add_component", "A", "", "Dummy", 80, 80),
          new Step("add_component", "B", "", "Dummy", 280, 80),
          new Step("add_hop", "A", "B", "", 50, 50));

  private HopGuidedPresentation packet(int revision, String mode, String state, List<Step> steps) {
    return new HopGuidedPresentation(
        id,
        revision,
        System.currentTimeMillis(),
        "test.hpl",
        "pipeline",
        mode,
        state,
        EMPTY,
        RESULT,
        steps);
  }

  @Test
  void roundTripsOnlyWhitelistedGeometryAndOmitsConfigurationValues() throws Exception {
    var operations =
        List.<Map<String, Object>>of(
            Map.of(
                "operation",
                "update_component",
                "component",
                "A",
                "properties",
                Map.of(
                    "PASSWORD",
                    "not-for-the-bridge",
                    "SQL",
                    "SELECT confidential",
                    "SCRIPT",
                    "private-script")));
    var packet = packet(1, "guided", "prepared", steps(operations));
    byte[] bytes = packet.encode();
    assertEquals(packet, decode(bytes));
    String content = new String(bytes, StandardCharsets.UTF_8);
    assertFalse(content.contains("not-for-the-bridge"));
    assertFalse(content.contains("confidential"));
    assertFalse(content.contains("private-script"));
    assertThrows(IOException.class, () -> decode(Arrays.copyOf(bytes, bytes.length + 1)));
    assertThrows(IOException.class, () -> decode(new byte[MAX_BYTES + 1]));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Graph(Collections.nCopies(MAX_NODES + 1, RESULT.nodes().getFirst()), List.of()));
  }

  @Test
  void acceptsNewBlocksWhilePlaybackIsStillBusyAndRejectsStaleRevisions() {
    var queue = new HopPresentationQueue();
    assertTrue(queue.accept(packet(1, "guided", "prepared", List.of())));
    assertTrue(queue.status().contains("Esperando"));
    assertTrue(queue.accept(packet(2, "guided", "prepared", STEPS.subList(0, 1))));
    queue.tick(100);
    queue.tick(200);
    assertEquals(0, queue.presented());
    assertTrue(queue.accept(packet(3, "guided", "prepared", STEPS)));
    assertEquals(3, queue.pending());
    assertFalse(queue.accept(packet(2, "guided", "prepared", STEPS)));
    assertTrue(queue.accept(packet(3, "guided", "applied", STEPS)));
    for (long now = 300; now < 12000; now += 100) queue.tick(now);
    assertTrue(queue.done());
    assertEquals(RESULT, queue.current());
    assertEquals(3, queue.presented());
  }

  @Test
  void pauseFreezesInterpolationAndInstantModeDoesNotWaitForFrames() {
    var queue = new HopPresentationQueue();
    queue.accept(packet(1, "guided", "prepared", STEPS));
    queue.tick(100);
    queue.tick(200);
    double fraction = queue.fraction();
    assertTrue(fraction > 0 && fraction < 1);
    queue.pause(true);
    queue.tick(300);
    queue.tick(400);
    assertEquals(fraction, queue.fraction());
    assertEquals(0, queue.presented());
    queue.pause(false);
    queue.speed(2);
    assertEquals(500, queue.duration());
    queue.accept(packet(1, "instant", "applied", STEPS));
    assertTrue(queue.done());
    assertEquals(RESULT, queue.current());
    assertEquals(0, queue.pending());
    assertThrows(IllegalArgumentException.class, () -> queue.speed(100));
  }

  @Test
  void autoPacingSlowsDownWhenTheKnownBufferIsLowAndCancellationTerminatesIt() {
    var queue = new HopPresentationQueue();
    queue.accept(packet(1, "guided", "prepared", Collections.nCopies(10, STEPS.getFirst())));
    long buffered = queue.duration();
    queue.tick(100);
    for (long time = 200; queue.pending() > 2; time += 100) queue.tick(time);
    assertTrue(queue.duration() > buffered);
    queue.accept(packet(1, "guided", "cancelled", Collections.nCopies(10, STEPS.getFirst())));
    assertTrue(queue.done());
    assertTrue(queue.status().contains("no aplicado"));
    assertFalse(queue.accept(packet(1, "guided", "prepared", STEPS)));
  }

  @Test
  void brokerKeepsLatestRevisionWithoutChangingLegacyControlBounds() throws Exception {
    var broker = new HopLiveUiEventBroker(project);
    assertFalse(broker.publishPresentation(packet(1, "guided", "prepared", STEPS)));
    try (var session = broker.openSession("desktop")) {
      assertTrue(broker.publishPresentation(packet(1, "guided", "prepared", STEPS)));
      assertTrue(broker.publishPresentation(packet(2, "instant", "applied", STEPS)));
      var received = broker.presentationsSince(0);
      assertEquals(1, received.size());
      assertEquals(2, received.getFirst().revision());
      assertEquals("instant", received.getFirst().mode());
      assertEquals(16 * 1024, HopLiveUiEventBroker.MAX_CONTROL_FILE_BYTES);
    }
  }

  @Test
  void brokerRejectsEscapedPathsAndIsolatesMalformedVisualPackets() throws Exception {
    var broker = new HopLiveUiEventBroker(project);
    try (var session = broker.openSession("desktop")) {
      var invalid =
          new HopGuidedPresentation(
              id, 1, 1, "../escape.hpl", "pipeline", "guided", "prepared", EMPTY, RESULT, STEPS);
      assertFalse(broker.publishPresentation(invalid));
      assertTrue(broker.publishPresentation(packet(1, "guided", "prepared", STEPS)));
      Files.write(
          project.resolve(".hop-mcp/events/" + UUID.randomUUID() + ".visual"),
          new byte[] {0, 1, 2});
      assertEquals(1, broker.presentationsSince(0).size());
    }
  }
}
