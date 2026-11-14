package io.github.michaaels.hop.mcp;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Boundary between semantic changes and session-aware Hop user interfaces. */
interface HopSemanticEventSink {
  HopSemanticEventSink NONE =
      new HopSemanticEventSink() {
        @Override
        public boolean publish(Event event) {
          return false;
        }
      };

  /** Publishes an event without blocking on UI work. */
  boolean publish(Event event);

  default boolean publishPresentation(HopGuidedPresentation presentation) {
    return false;
  }

  default boolean publishPresentation(
      HopGuidedPresentation presentation, HopNativeDraft nativeDraft) {
    return publishPresentation(presentation);
  }

  /** Returns whether at least one UI session is currently available to consume events. */
  default boolean isAvailable() {
    return false;
  }

  /** Returns transport-specific delivery state without exposing UI session identifiers. */
  default Map<String, Object> status(String transactionId) throws IOException {
    return Map.of(
        "available",
        false,
        "adapter",
        "none",
        "transaction_id",
        transactionId == null ? "" : transactionId,
        "acknowledgements",
        List.of());
  }

  record Event(
      String type,
      String path,
      String definitionKind,
      String transactionId,
      String oldSha256,
      String newSha256,
      List<Map<String, Object>> changes) {
    public Event {
      changes = changes == null ? List.of() : List.copyOf(changes);
    }
  }
}
