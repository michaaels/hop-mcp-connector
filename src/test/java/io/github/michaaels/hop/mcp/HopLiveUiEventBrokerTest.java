package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HopLiveUiEventBrokerTest {
  private static final String ZERO_SHA = "0".repeat(64);
  private static final String ONE_SHA = "1".repeat(64);

  @TempDir Path projectRoot;

  @Test
  void publishesBoundedEventsOnlyWhileAUiSessionIsLive() throws Exception {
    HopLiveUiEventBroker publisher = new HopLiveUiEventBroker(projectRoot);
    HopLiveUiEventBroker consumer = new HopLiveUiEventBroker(projectRoot);
    HopSemanticEventSink.Event semanticEvent = semanticEvent("pipelines/orders.hpl");

    assertFalse(publisher.isAvailable());
    assertFalse(publisher.publish(semanticEvent));

    try (HopLiveUiEventBroker.LiveSession session = consumer.openSession("desktop");
        HopLiveUiEventBroker.LiveSession webSession = consumer.openSession("web")) {
      assertTrue(publisher.isAvailable());
      assertTrue(publisher.publish(semanticEvent));

      List<HopLiveUiEventBroker.LiveEvent> events =
          consumer.eventsSince(System.currentTimeMillis() - 5_000L);
      assertEquals(1, events.size());
      HopLiveUiEventBroker.LiveEvent event = events.get(0);
      assertEquals("mutation_applied", event.type());
      assertEquals("pipelines/orders.hpl", event.path());
      assertEquals("pipeline", event.definitionKind());

      consumer.acknowledge(session, event, "reloaded", "Reloaded safely");
      Map<String, Object> status = publisher.status(semanticEvent.transactionId());
      assertEquals(true, status.get("available"));
      assertEquals(2, status.get("active_sessions"));
      assertEquals(Map.of("desktop", 1, "web", 1), status.get("active_clients"));
      assertEquals(1, status.get("acknowledgement_count"));
      Map<?, ?> acknowledgement = (Map<?, ?>) ((List<?>) status.get("acknowledgements")).get(0);
      assertEquals("reloaded", acknowledgement.get("status"));
      assertEquals("desktop", acknowledgement.get("client_type"));
      try (var paths =
          Files.list(
              projectRoot
                  .resolve(HopLiveUiEventBroker.CONTROL_DIRECTORY)
                  .resolve("acknowledgements"))) {
        assertEquals(1, paths.count());
      }
    }

    assertFalse(publisher.isAvailable());
  }

  @Test
  void keepsControlFilesOutOfProjectTools() throws Exception {
    HopLiveUiEventBroker broker = new HopLiveUiEventBroker(projectRoot);
    try (HopLiveUiEventBroker.LiveSession ignored = broker.openSession("desktop")) {
      ProjectFiles projectFiles = new ProjectFiles(projectRoot);
      Map<String, Object> catalog = projectFiles.catalog("**", 0, 200);

      assertEquals(0, catalog.get("count"));
      assertTrue(projectFiles.definitions().isEmpty());
      assertThrows(
          Exception.class,
          () ->
              projectFiles.readText(
                  HopLiveUiEventBroker.CONTROL_DIRECTORY + "/sessions/not-a-session"));
      assertThrows(
          Exception.class,
          () ->
              projectFiles.resolveForWrite(
                  HopLiveUiEventBroker.CONTROL_DIRECTORY + "/events/injected.event"));
    }
  }

  @Test
  void rejectsUntrustedEventPathsAndFingerprints() throws Exception {
    HopLiveUiEventBroker broker = new HopLiveUiEventBroker(projectRoot);
    try (HopLiveUiEventBroker.LiveSession ignored = broker.openSession("desktop")) {
      assertFalse(broker.publish(semanticEvent("../outside.hpl")));
      assertFalse(
          broker.publish(
              new HopSemanticEventSink.Event(
                  "mutation_applied",
                  "safe.hpl",
                  "pipeline",
                  UUID.randomUUID().toString(),
                  "not-a-hash",
                  ONE_SHA,
                  List.of())));
    }
  }

  @Test
  void boundsRetainedEventFiles() throws Exception {
    HopLiveUiEventBroker broker = new HopLiveUiEventBroker(projectRoot);
    try (HopLiveUiEventBroker.LiveSession ignored = broker.openSession("desktop")) {
      for (int index = 0; index < HopLiveUiEventBroker.MAX_EVENT_FILES + 10; index++) {
        assertTrue(broker.publish(semanticEvent("pipelines/bounded.hpl")));
      }
      try (var paths =
          Files.list(
              projectRoot.resolve(HopLiveUiEventBroker.CONTROL_DIRECTORY).resolve("events"))) {
        assertTrue(paths.count() <= HopLiveUiEventBroker.MAX_EVENT_FILES);
      }
    }
  }

  private static HopSemanticEventSink.Event semanticEvent(String path) {
    return new HopSemanticEventSink.Event(
        "mutation_applied",
        path,
        "pipeline",
        UUID.randomUUID().toString(),
        ZERO_SHA,
        ONE_SHA,
        List.of(Map.of("operation", "set_name")));
  }

  @Test
  void targetedWebEventsAndDraftsReachOnlyTheSelectedSessionOnTheSameProject() throws Exception {
    var broker = new HopLiveUiEventBroker(projectRoot);
    try (var first = broker.openSession("web");
        var second = broker.openSession("web")) {
      var targeted = new HopLiveUiEventBroker(projectRoot, first.id());
      assertTrue(targeted.publish(semanticEvent("private.hpl")));
      assertEquals(1, broker.eventsSince(0, first.id()).size());
      assertTrue(broker.eventsSince(0, second.id()).isEmpty());
      var graph = new HopGuidedPresentation.Graph(List.of(), List.of());
      var presentation =
          new HopGuidedPresentation(
              UUID.randomUUID().toString(),
              1,
              System.currentTimeMillis(),
              "private.hpl",
              "pipeline",
              "guided",
              "prepared",
              graph,
              graph,
              List.of());
      assertTrue(targeted.publishPresentation(presentation));
      assertEquals(1, broker.presentationsSince(0, first.id()).size());
      assertTrue(broker.presentationsSince(0, second.id()).isEmpty());
      assertTrue(broker.publish(semanticEvent("legacy-broadcast.hpl")));
      assertEquals(1, broker.eventsSince(0, second.id()).size());
      assertEquals("legacy-broadcast.hpl", broker.eventsSince(0, second.id()).getFirst().path());
    }
  }
}
