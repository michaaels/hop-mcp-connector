package io.github.michaaels.hop.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.hop.ui.hopgui.HopGui;
import org.apache.hop.ui.hopgui.file.IHopFileType;
import org.apache.hop.ui.hopgui.file.IHopFileTypeHandler;
import org.apache.hop.ui.hopgui.perspective.TabItemHandler;
import org.apache.hop.ui.hopgui.perspective.explorer.ExplorerPerspective;
import org.eclipse.swt.widgets.Display;

/**
 * Common lifecycle and event handling for one Hop UI session.
 *
 * <p>Each concrete adapter owns exactly one broker session. Filesystem polling happens on a daemon
 * thread, while every interaction with Hop UI objects is marshalled onto the session's SWT display.
 * Dirty tabs are never reloaded or closed.
 */
abstract class HopLiveUiSync implements AutoCloseable {
  private static final long EVENT_POLL_MILLISECONDS = 500L;
  private static final long HEARTBEAT_SECONDS = 10L;
  private static final int MAX_SEEN_EVENTS = HopLiveUiEventBroker.MAX_EVENT_FILES * 2;

  private final HopGui hopGui;
  private final Path projectRoot;
  private final HopLiveUiEventBroker broker;
  private final ScheduledExecutorService scheduler;
  private final Set<String> seenEventIds = new HashSet<>();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final long startedAt;
  private final String clientType;
  private final Runnable closedCallback;

  private HopLiveUiEventBroker.LiveSession session;
  private boolean uiDispatchStarted;

  HopLiveUiSync(HopGui hopGui, Path projectRoot, String clientType, Runnable closedCallback)
      throws IOException {
    if (hopGui == null) {
      throw new IllegalArgumentException("hopGui is required");
    }
    this.hopGui = hopGui;
    this.projectRoot = projectRoot.toAbsolutePath().normalize().toRealPath();
    this.broker = new HopLiveUiEventBroker(this.projectRoot);
    this.clientType = clientType;
    this.closedCallback = closedCallback == null ? () -> {} : closedCallback;
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "hop-mcp-" + clientType + "-live-sync");
              thread.setDaemon(true);
              return thread;
            });
    this.startedAt = System.currentTimeMillis();
  }

  synchronized void start() throws IOException {
    if (session != null) {
      return;
    }
    if (closed.get()) {
      throw new IllegalStateException("Live synchronization is closed");
    }

    boolean started = false;
    try {
      startUiDispatch();
      uiDispatchStarted = true;
      session = broker.openSession(clientType);
      scheduler.scheduleWithFixedDelay(
          this::pollSafely, 0L, EVENT_POLL_MILLISECONDS, TimeUnit.MILLISECONDS);
      scheduler.scheduleWithFixedDelay(
          this::heartbeatSafely, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
      hopGui.getShell().addDisposeListener(event -> closeQuietly());
      started = true;
    } finally {
      if (!started) {
        stopAfterFailedStart();
      }
    }
  }

  boolean isRunning() {
    return session != null && !closed.get();
  }

  Path projectRoot() {
    return projectRoot;
  }

  String clientType() {
    return clientType;
  }

  /** Starts platform-specific support while still on the owning UI session thread. */
  protected void startUiDispatch() {}

  /** Stops platform-specific support while still associated with the owning display. */
  protected void stopUiDispatch() {}

  private void pollSafely() {
    if (closed.get()) {
      return;
    }
    try {
      for (HopLiveUiEventBroker.LiveEvent event : broker.eventsSince(startedAt)) {
        if (remember(event.eventId())) {
          dispatch(event);
        }
      }
    } catch (Exception e) {
      hopGui.getLog().logError("MCP Connector for Apache Hop live synchronization poll failed", e);
    }
  }

  private void heartbeatSafely() {
    if (closed.get()) {
      return;
    }
    try {
      broker.heartbeat(session);
    } catch (Exception e) {
      hopGui
          .getLog()
          .logError("MCP Connector for Apache Hop live synchronization heartbeat failed", e);
    }
  }

  private synchronized boolean remember(String eventId) {
    if (seenEventIds.size() >= MAX_SEEN_EVENTS) {
      seenEventIds.clear();
    }
    return seenEventIds.add(eventId);
  }

  private void dispatch(HopLiveUiEventBroker.LiveEvent event) {
    Display display = hopGui.getShell().getDisplay();
    if (display == null || display.isDisposed()) {
      return;
    }
    display.asyncExec(
        () -> {
          if (!closed.get() && !hopGui.getShell().isDisposed()) {
            applyToUi(event);
          }
        });
  }

  private void applyToUi(HopLiveUiEventBroker.LiveEvent event) {
    try {
      Path definition = resolveDefinition(event.path());
      ExplorerPerspective perspective = ExplorerPerspective.getInstance();
      IHopFileTypeHandler handler = findOpenHandler(perspective, definition);

      if (handler != null && handler.hasChanged()) {
        acknowledge(event, "skipped_dirty", "The open tab has unsaved changes");
        return;
      }

      if (!Files.exists(definition)) {
        if (handler == null) {
          acknowledge(event, "not_open", "The rolled-back definition is not open");
        } else {
          perspective.closeTabsForFilenames(List.of(handler.getFilename()));
          acknowledge(event, "closed", "The rolled-back created definition was closed");
        }
        return;
      }

      if (handler != null) {
        handler.reload();
        acknowledge(event, "reloaded", "The open definition was reloaded from disk");
        return;
      }

      IHopFileType fileType = perspective.getFileType(definition.toString());
      fileType.openFile(hopGui, definition.toString(), hopGui.getVariables());
      acknowledge(event, "opened", "The changed definition was opened in " + clientLabel());
    } catch (Exception e) {
      hopGui
          .getLog()
          .logError(
              "Unable to apply MCP Connector for Apache Hop live event for '" + event.path() + "'",
              e);
      acknowledge(event, "error", "Unable to apply the live event; see the Hop log");
    }
  }

  private String clientLabel() {
    return "web".equals(clientType) ? "Hop Web" : "Hop Desktop";
  }

  private Path resolveDefinition(String relativePath) throws IOException {
    Path resolved = projectRoot.resolve(relativePath).normalize();
    if (!resolved.startsWith(projectRoot)) {
      throw new IOException("Live event path escaped the project root");
    }
    return resolved;
  }

  private IHopFileTypeHandler findOpenHandler(ExplorerPerspective perspective, Path definition) {
    for (TabItemHandler tab : perspective.getTabItemHandlersInPaneOrder()) {
      IHopFileTypeHandler handler = tab.getTypeHandler();
      String filename = handler.getFilename();
      if (filename == null || filename.isBlank()) {
        continue;
      }
      try {
        Path openPath =
            Path.of(hopGui.getVariables().resolve(filename)).toAbsolutePath().normalize();
        if (definition.equals(openPath)) {
          return handler;
        }
      } catch (RuntimeException ignored) {
        // Non-local VFS handlers cannot match this project-local event.
      }
    }
    return null;
  }

  private void acknowledge(HopLiveUiEventBroker.LiveEvent event, String status, String message) {
    try {
      broker.acknowledge(session, event, status, message);
    } catch (Exception e) {
      hopGui.getLog().logError("Unable to acknowledge MCP Connector for Apache Hop live event", e);
    }
  }

  @Override
  public synchronized void close() throws IOException {
    if (!closed.compareAndSet(false, true)) {
      return;
    }

    IOException failure = null;
    scheduler.shutdownNow();
    if (session != null) {
      try {
        session.close();
      } catch (IOException e) {
        failure = e;
      } finally {
        session = null;
      }
    }
    if (uiDispatchStarted) {
      try {
        stopUiDispatch();
      } catch (RuntimeException e) {
        if (failure == null) {
          failure = new IOException("Unable to stop UI dispatch support", e);
        } else {
          failure.addSuppressed(e);
        }
      } finally {
        uiDispatchStarted = false;
      }
    }
    closedCallback.run();
    if (failure != null) {
      throw failure;
    }
  }

  private void stopAfterFailedStart() {
    scheduler.shutdownNow();
    if (session != null) {
      try {
        session.close();
      } catch (IOException e) {
        hopGui.getLog().logError("Unable to close failed live synchronization session", e);
      } finally {
        session = null;
      }
    }
    if (uiDispatchStarted) {
      try {
        stopUiDispatch();
      } catch (RuntimeException e) {
        hopGui.getLog().logError("Unable to stop failed live synchronization UI dispatch", e);
      } finally {
        uiDispatchStarted = false;
      }
    }
  }

  private void closeQuietly() {
    try {
      close();
    } catch (IOException e) {
      hopGui
          .getLog()
          .logError("Unable to stop MCP Connector for Apache Hop live synchronization", e);
    }
  }
}
