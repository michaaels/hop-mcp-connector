package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HopNativeDraftTest {
  @TempDir Path project;

  private HopGuidedPresentation packet(String id, HopNativeDraft draft) throws Exception {
    var empty = new HopGuidedPresentation.Graph(List.of(), List.of());
    return new HopGuidedPresentation(
        id,
        1,
        System.currentTimeMillis(),
        "test.hpl",
        "pipeline",
        "guided",
        "prepared",
        empty,
        empty,
        List.of(),
        ProjectFiles.sha256(draft.encode()));
  }

  @Test
  void privateNativeSnapshotRoundTripsAndRejectsUnboundedOrMalformedContent() throws Exception {
    var draft = new HopNativeDraft("<pipeline/>", "<pipeline><name>á</name></pipeline>");
    byte[] bytes = draft.encode();
    assertEquals(draft, HopNativeDraft.decode(bytes));
    assertThrows(
        IOException.class, () -> HopNativeDraft.decode(Arrays.copyOf(bytes, bytes.length + 1)));
    assertThrows(IOException.class, () -> HopNativeDraft.decode(new byte[] {-1, -1, -1, -1}));
    assertThrows(
        IOException.class,
        () -> new HopNativeDraft("x".repeat(HopNativeDraft.MAX_BYTES), "").encode());
  }

  @Test
  void guiReadsFullNativeConfigurationThroughPrivateFingerprintCheckedStorageOnly()
      throws Exception {
    var broker = new HopLiveUiEventBroker(project);
    var draft =
        new HopNativeDraft("<pipeline/>", "<pipeline><password>local-secret</password></pipeline>");
    var packet = packet(UUID.randomUUID().toString(), draft);
    assertFalse(broker.publishPresentation(packet, draft));
    try (var session = broker.openSession("desktop")) {
      assertTrue(broker.publishPresentation(packet, draft));
      assertEquals(draft, broker.readDraft(packet));
      assertFalse(new String(packet.encode(), StandardCharsets.UTF_8).contains("local-secret"));
      assertFalse(Files.exists(project.resolve("test.hpl")));
      Files.writeString(project.resolve(".hop-mcp/drafts/" + packet.id() + ".draft"), "tampered");
      assertThrows(IOException.class, () -> broker.readDraft(packet));
    }
  }

  @Test
  void webOnlySessionPublishesNativeDraftAndInstantCompletionWhileUnknownClientsDoNot()
      throws Exception {
    var broker = new HopLiveUiEventBroker(project);
    var draft = new HopNativeDraft("<pipeline/>", "<pipeline><name>Web</name></pipeline>");
    var packet = packet(UUID.randomUUID().toString(), draft);
    try (var unsupported = broker.openSession("other")) {
      assertFalse(broker.publishPresentation(packet, draft));
    }
    try (var web = broker.openSession("web")) {
      assertTrue(broker.publishPresentation(packet, draft));
      assertEquals(draft, broker.readDraft(packet));
      var completed =
          new HopGuidedPresentation(
              packet.id(),
              packet.revision(),
              System.currentTimeMillis(),
              packet.path(),
              packet.kind(),
              "instant",
              "applied",
              packet.baseline(),
              packet.result(),
              packet.steps(),
              packet.snapshotSha256());
      assertTrue(broker.publishPresentation(completed));
      assertEquals(List.of(completed), broker.presentationsSince(0));
      assertFalse(Files.exists(project.resolve("test.hpl")));
    }
  }

  @Test
  void privateDraftsRespectStorageBudgetAndExpiry() throws Exception {
    var broker = new HopLiveUiEventBroker(project);
    try (var session = broker.openSession("desktop")) {
      var draft = new HopNativeDraft("", "x".repeat(2 * 1024 * 1024));
      for (int i = 0; i < 17; i++)
        assertTrue(broker.publishPresentation(packet(UUID.randomUUID().toString(), draft), draft));
      Path folder = project.resolve(".hop-mcp/drafts");
      try (var paths = Files.list(folder)) {
        assertTrue(
            paths
                    .mapToLong(
                        p -> {
                          try {
                            return Files.size(p);
                          } catch (IOException e) {
                            throw new RuntimeException(e);
                          }
                        })
                    .sum()
                <= HopLiveUiEventBroker.MAX_DRAFT_STORAGE_BYTES);
      }
      Path expired;
      try (var paths = Files.list(folder)) {
        expired = paths.findFirst().orElseThrow();
      }
      Files.setLastModifiedTime(
          expired, FileTime.fromMillis(System.currentTimeMillis() - 3_600_001));
      assertTrue(broker.publishPresentation(packet(UUID.randomUUID().toString(), draft), draft));
      assertFalse(Files.exists(expired));
    }
  }

  @Test
  void draftDirectoryCannotRedirectOutsideTheProject() throws Exception {
    var broker = new HopLiveUiEventBroker(project);
    try (var session = broker.openSession("desktop")) {
      var draft = new HopNativeDraft("", "");
      // A regular file also rejects the same non-directory boundary without symlink privileges.
      Files.writeString(project.resolve(".hop-mcp/drafts"), "occupied");
      assertFalse(broker.publishPresentation(packet(UUID.randomUUID().toString(), draft), draft));
    }
  }
}
