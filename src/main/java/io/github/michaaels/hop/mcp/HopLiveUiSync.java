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
  private final java.util.Map<String, HopGuidedView> guidedViews = new java.util.LinkedHashMap<>();
  private final java.util.Map<String, HopLiveUiEventBroker.LiveEvent> deferredReloads =
      new java.util.LinkedHashMap<>();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final long startedAt;
  private final String clientType;
  private final Runnable closedCallback;

  private HopLiveUiEventBroker.LiveSession session;
  private boolean uiDispatchStarted;
  private HopLiveIndicator indicator;
  private long uiActivityUntil;
  private volatile long acceptSince;

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
    acceptSince = startedAt;
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
      session = broker.openSession(clientType);
      startUiDispatch();
      uiDispatchStarted = true;
      indicator = new HopLiveIndicator(hopGui, this::indicatorActivity, this::disconnectAgent);
      scheduler.scheduleWithFixedDelay(
          this::pollSafely, 0L, EVENT_POLL_MILLISECONDS, TimeUnit.MILLISECONDS);
      scheduler.scheduleWithFixedDelay(
          this::heartbeatSafely, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
      hopGui
          .getShell()
          .addDisposeListener(
              event -> {
                // The shell owns child disposal; removing tabs here can touch released RAP images.
                guidedViews.clear();
                deferredReloads.clear();
                if (indicator != null) {
                  indicator.close(false);
                  indicator = null;
                }
                closeQuietly();
              });
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

  /** Web overrides this with its request counter; Desktop observes native presentation/events. */
  protected boolean requestsInProgress() {
    return false;
  }

  protected boolean isWorking() {
    return requestsInProgress()
        || (uiActivityUntil != 0 && System.nanoTime() - uiActivityUntil < 0)
        || isPresenting();
  }

  protected boolean isPresenting() {
    return guidedViews.values().stream().anyMatch(HopGuidedView::isWorking);
  }

  protected HopAgentActivity indicatorActivity() {
    return HopAgentActivity.disconnected();
  }

  protected void disconnectAgent() {
    closeQuietly();
  }

  protected boolean receiveAgentEvents() {
    return true;
  }

  protected long eventEpoch() {
    return acceptSince;
  }

  protected HopLiveUiEventBroker agentEvents() throws IOException {
    return new HopLiveUiEventBroker(projectRoot, session.id());
  }

  protected void clearAgentDrafts() {
    acceptSince = System.currentTimeMillis();
    deferredReloads.clear();
    guidedViews.values().forEach(HopGuidedView::close);
    guidedViews.clear();
    synchronized (this) {
      seenEventIds.clear();
    }
  }

  private void pollSafely() {
    if (closed.get() || !receiveAgentEvents()) {
      return;
    }
    try {
      var current = session;
      if (current == null) return;
      long since = Math.max(acceptSince, eventEpoch());
      pollPresentations(since, current.id());
      for (HopLiveUiEventBroker.LiveEvent event : broker.eventsSince(since, current.id())) {
        if (remember(event.eventId())) {
          dispatch(event);
        }
      }
    } catch (Exception e) {
      hopGui.getLog().logError("MCP Live Sync poll failed", e);
    }
  }

  private void pollPresentations(long since, String receiver) throws IOException {
    for (var presentation : broker.presentationsSince(since, receiver)) {
      String key =
          "visual:"
              + presentation.id()
              + ":"
              + presentation.revision()
              + ":"
              + presentation.state()
              + ":"
              + presentation.mode();
      if (seenEventIds.contains(key)) continue;
      HopNativeDraft draft;
      try {
        draft = broker.readDraft(presentation);
      } catch (IOException unavailable) {
        // A newer revision can replace its snapshot before its packet; retry on the next poll.
        continue;
      }
      if (remember(key)) {
        hopGui
            .getShell()
            .getDisplay()
            .asyncExec(
                () -> {
                  if (!closed.get() && receiveAgentEvents() && !hopGui.getShell().isDisposed())
                    showPresentation(presentation, draft);
                });
      }
    }
  }

  private void heartbeatSafely() {
    if (closed.get()) {
      return;
    }
    try {
      broker.heartbeat(session);
    } catch (Exception e) {
      hopGui.getLog().logError("MCP Live Sync heartbeat failed", e);
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
          if (!closed.get() && receiveAgentEvents() && !hopGui.getShell().isDisposed()) {
            applyToUi(event);
          }
        });
  }

  private void applyToUi(HopLiveUiEventBroker.LiveEvent event) {
    uiActivityUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2400);
    try {
      for (var view : guidedViews.values()) {
        if (view.path().equals(event.path()) && view.defersReload()) {
          deferredReloads.put(event.path(), event);
          return;
        }
      }
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
      hopGui.getLog().logError("Unable to apply MCP Live Sync event for '" + event.path() + "'", e);
      acknowledge(event, "error", "Unable to apply the live event; see the Hop log");
    }
  }

  private void showPresentation(HopGuidedPresentation presentation, HopNativeDraft draft) {
    try {
      showNativePresentation(presentation, draft);
    } catch (Exception failure) {
      hopGui
          .getLog()
          .logError(
              "Unable to display native MCP draft; the plan remains available through MCP ("
                  + failure.getClass().getSimpleName()
                  + ").");
    }
  }

  private void showNativePresentation(HopGuidedPresentation presentation, HopNativeDraft draft)
      throws Exception {
    HopGuidedView view = guidedViews.get(presentation.id());
    if (view == null) {
      if (!presentation.mode().equals("guided")) return;
      while (guidedViews.size() >= 4) {
        String oldest = guidedViews.keySet().iterator().next();
        guidedViews.remove(oldest).close();
      }
      view =
          new HopGuidedView(
              hopGui, projectRoot, presentation, draft, () -> flushReload(presentation.path()));
      guidedViews.put(presentation.id(), view);
    } else view.accept(presentation, draft);
  }

  private void flushReload(String path) {
    var event = deferredReloads.remove(path);
    if (event != null && !closed.get()) applyToUi(event);
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
      hopGui.getLog().logError("Unable to acknowledge MCP Live Sync event", e);
    }
  }

  @Override
  public synchronized void close() throws IOException {
    if (!closed.compareAndSet(false, true)) {
      return;
    }

    IOException failure = null;
    scheduler.shutdownNow();
    Display display = hopGui.getShell().getDisplay();
    Runnable disposeViews =
        () -> {
          if (indicator != null) {
            indicator.close();
            indicator = null;
          }
          guidedViews.values().forEach(HopGuidedView::close);
          guidedViews.clear();
          deferredReloads.clear();
        };
    if (!display.isDisposed()) {
      if (Display.getCurrent() == display) disposeViews.run();
      else display.asyncExec(disposeViews);
    }
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
    if (indicator != null) {
      indicator.close();
      indicator = null;
    }
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
      hopGui.getLog().logError("Unable to stop MCP Live Sync", e);
    }
  }
}
