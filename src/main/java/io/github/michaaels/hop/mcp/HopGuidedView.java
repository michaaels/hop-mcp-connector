package io.github.michaaels.hop.mcp;

import java.nio.file.Path;
import java.util.Set;
import org.apache.hop.ui.core.gui.GuiResource;
import org.apache.hop.ui.hopgui.HopGui;
import org.apache.hop.ui.hopgui.perspective.TabItemHandler;
import org.apache.hop.ui.hopgui.perspective.explorer.ExplorerPerspective;
import org.apache.hop.ui.util.EnvironmentUtils;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

/**
 * Playback controls embedded above a real native Hop editor; no extra window or custom renderer.
 */
final class HopGuidedView implements AutoCloseable {
  private final HopPresentationQueue queue = new HopPresentationQueue();
  private final Composite container;
  private final CTabItem tab;
  private final HopNativeEditor editor;
  private final Label status;
  private final Button pause;
  private final Combo speed;
  private final Runnable completion;
  private final ExplorerPerspective perspective;
  private final int frameInterval;
  private boolean notified;
  private boolean disposed;
  private int lastPresented = -1;
  private int lastRevision = -1;
  private String lastStatus = "";

  HopGuidedView(
      HopGui hopGui,
      Path projectRoot,
      HopGuidedPresentation initial,
      HopNativeDraft draft,
      Runnable completion)
      throws Exception {
    this.completion = completion;
    frameInterval = EnvironmentUtils.getInstance().isWeb() ? 100 : 33;
    perspective = ExplorerPerspective.getInstance();
    var folder = perspective.getTabFolder();
    container = new Composite(folder, SWT.NONE);
    var layout = new GridLayout(1, false);
    layout.marginHeight = 0;
    layout.marginWidth = 0;
    container.setLayout(layout);
    var controls = new Composite(container, SWT.NONE);
    controls.setLayout(new GridLayout(5, false));
    controls.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    new Label(controls, SWT.NONE).setText("MCP · Presentación:");
    speed = new Combo(controls, SWT.READ_ONLY);
    speed.setItems(new String[] {"Auto", "0.5×", "1×", "2×", "Instantáneo"});
    speed.select(0);
    speed.addListener(
        SWT.Selection,
        event -> {
          if (speed.getSelectionIndex() == 4) queue.instantaneous();
          else queue.speed(new double[] {0, .5, 1, 2}[speed.getSelectionIndex()]);
          refresh(true);
        });
    pause = new Button(controls, SWT.PUSH);
    pause.setText("Pausar");
    pause.addListener(
        SWT.Selection,
        event -> {
          queue.pause(!queue.paused());
          refresh(false);
        });
    var skip = new Button(controls, SWT.PUSH);
    skip.setText("Saltar al resultado");
    skip.addListener(
        SWT.Selection,
        event -> {
          queue.skip();
          refresh(true);
        });
    status = new Label(controls, SWT.NONE);
    status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    try {
      editor =
          new HopNativeEditor(
              container,
              hopGui,
              initial,
              draft,
              () -> status.setText("Borrador: aplicar el plan antes de guardar o ejecutar."));
    } catch (Exception failure) {
      container.dispose();
      throw failure;
    }
    editor.control().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    String extension = initial.kind().equals("pipeline") ? ".hpl" : ".hwf";
    editor
        .handler()
        .setFilename(
            projectRoot
                .resolve(".hop-mcp/drafts/" + initial.id() + "-view" + extension)
                .toString());
    tab = new CTabItem(folder, SWT.CLOSE);
    tab.setText("MCP · " + Path.of(initial.path()).getFileName());
    tab.setToolTipText("Borrador guiado · " + initial.path());
    tab.setImage(
        initial.kind().equals("pipeline")
            ? GuiResource.getInstance().getImagePipeline()
            : GuiResource.getInstance().getImageWorkflow());
    tab.setControl(container);
    tab.setData(editor.handler());
    perspective.getItems().add(new TabItemHandler(tab, editor.handler()));
    tab.addDisposeListener(event -> dispose());
    container.addDisposeListener(event -> dispose());
    folder.setSelection(tab);
    perspective.setActiveFileTypeHandler(editor.handler());
    perspective.activate();
    queue.accept(initial);
    try {
      refresh(true);
    } catch (RuntimeException failure) {
      close();
      throw failure;
    }
    folder.getDisplay().timerExec(frameInterval, this::frame);
  }

  boolean accept(HopGuidedPresentation presentation, HopNativeDraft draft) throws Exception {
    if (disposed) return false;
    var previous = queue.plan();
    if (!queue.accept(presentation)) return false;
    if (previous == null || !previous.snapshotSha256().equals(presentation.snapshotSha256()))
      editor.update(draft);
    notified = false;
    if (presentation.mode().equals("instant")) speed.select(4);
    refresh(true);
    return true;
  }

  boolean finished() {
    return disposed || queue.done();
  }

  boolean isWorking() {
    return !disposed && !queue.paused() && queue.pending() > 0;
  }

  boolean defersReload() {
    return !finished()
        && !queue.plan().mode().equals("instant")
        && Set.of("applying", "applied").contains(queue.plan().state());
  }

  String path() {
    return queue.plan().path();
  }

  private void frame() {
    if (disposed || container.isDisposed()) return;
    int before = queue.presented();
    double fractionBefore = queue.fraction();
    queue.tick(System.currentTimeMillis());
    boolean moving = queue.pending() > 0 && !queue.paused();
    refresh(before != queue.presented() || fractionBefore != queue.fraction());
    if (!disposed) container.getDisplay().timerExec(moving ? frameInterval : 250, this::frame);
  }

  private void refresh(boolean redraw) {
    if (disposed) return;
    if (redraw) editor.render(queue);
    var plan = queue.plan();
    String message = queue.status();
    if (!message.equals(lastStatus)
        || lastPresented != queue.presented()
        || lastRevision != plan.revision()) {
      lastStatus = message;
      lastPresented = queue.presented();
      lastRevision = plan.revision();
      String action = queue.active() == null ? "" : " · " + queue.active().label();
      status.setText(message + action);
      pause.setText(queue.paused() ? "Continuar" : "Pausar");
      status.getParent().layout();
    }
    if (!notified && queue.done()) {
      notified = true;
      close();
    }
  }

  @Override
  public void close() {
    if (disposed) return;
    perspective.remove(editor.handler());
    if (!tab.isDisposed()) tab.dispose();
    if (!container.isDisposed()) container.dispose();
    dispose();
  }

  private void dispose() {
    if (disposed) return;
    disposed = true;
    if (!container.isDisposed()) container.dispose();
    completion.run();
  }
}
