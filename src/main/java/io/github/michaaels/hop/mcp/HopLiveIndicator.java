package io.github.michaaels.hop.mcp;

import java.util.function.Supplier;
import org.apache.hop.core.SwtUniversalImage;
import org.apache.hop.ui.hopgui.HopGui;
import org.apache.hop.ui.util.EnvironmentUtils;
import org.apache.hop.ui.util.SwtSvgImageUtil;
import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleControlAdapter;
import org.eclipse.swt.accessibility.AccessibleControlEvent;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.layout.FormAttachment;
import org.eclipse.swt.layout.FormData;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Control;

/** One session-owned control in Hop's footer; no floating shell or separate application. */
final class HopLiveIndicator implements AutoCloseable {
  private static final int CONTROL_WIDTH = 24;
  private static final int ICON_SIZE = 16;
  private final Canvas control;
  private final Control toolbar;
  private final FormData toolbarLayout;
  private final FormAttachment previousRight;
  private final FormAttachment reservedRight;
  private final SwtUniversalImage logo;
  private final SwtUniversalImage disconnectedLogo;
  private final Supplier<HopAgentActivity> activity;
  private final Runnable tick = this::frame;
  private final boolean web = EnvironmentUtils.getInstance().isWeb();
  private String description;
  private int alpha = 255;
  private long pulseStarted;
  private boolean wasWorking;
  private boolean closed;
  private HopAgentActivity state = HopAgentActivity.disconnected();

  HopLiveIndicator(HopGui gui, Supplier<HopAgentActivity> activity, Runnable stop) {
    this.activity = activity;
    toolbar = gui.getStatusToolbar();
    if (toolbar == null || !(toolbar.getLayoutData() instanceof FormData layout)) {
      throw new IllegalStateException("Hop status toolbar is unavailable");
    }
    toolbarLayout = layout;
    previousRight = layout.right;
    control = new Canvas(gui.getShell(), SWT.DOUBLE_BUFFERED);
    control.setData("mcp-live-indicator", Boolean.TRUE);
    if (web) HopWebIndicatorTooltip.enable(control);
    control.setBackground(toolbar.getBackground());
    control.setCursor(control.getDisplay().getSystemCursor(SWT.CURSOR_HAND));
    var position = new FormData(CONTROL_WIDTH, SWT.DEFAULT);
    position.right = new FormAttachment(100, -4);
    position.top = new FormAttachment(toolbar, 0, SWT.TOP);
    position.bottom = new FormAttachment(toolbar, 0, SWT.BOTTOM);
    control.setLayoutData(position);
    reservedRight = new FormAttachment(control, -4);
    layout.right = reservedRight;
    logo =
        SwtSvgImageUtil.getUniversalImage(
            control.getDisplay(), getClass().getClassLoader(), "mcp/model-context-protocol.svg");
    disconnectedLogo =
        SwtSvgImageUtil.getUniversalImage(
            control.getDisplay(),
            getClass().getClassLoader(),
            "mcp/model-context-protocol-disconnected.svg");
    control.addPaintListener(
        event -> {
          event.gc.setAlpha(alpha);
          var area = control.getClientArea();
          int size = Math.min(ICON_SIZE, Math.min(area.width, area.height));
          if (size <= 0) return;
          event.gc.drawImage(
              (state.connected() ? logo : disconnectedLogo)
                  .getAsBitmapForSize(control.getDisplay(), size, size),
              (area.width - size) / 2,
              (area.height - size) / 2);
          event.gc.setAlpha(255);
          if (control.isFocusControl())
            event.gc.drawFocus(2, 2, Math.max(0, area.width - 4), Math.max(0, area.height - 4));
        });
    control.addListener(
        SWT.MouseUp,
        event -> {
          if (event.button == 1) stop.run();
        });
    control.addListener(
        SWT.KeyDown,
        event -> {
          if (event.keyCode == SWT.CR || event.character == ' ') {
            event.doit = false;
            stop.run();
          }
        });
    control.addListener(
        SWT.Traverse,
        event -> {
          if (event.detail == SWT.TRAVERSE_TAB_NEXT || event.detail == SWT.TRAVERSE_TAB_PREVIOUS)
            event.doit = true;
        });
    control.addListener(SWT.FocusIn, event -> control.redraw());
    control.addListener(SWT.FocusOut, event -> control.redraw());
    control
        .getAccessible()
        .addAccessibleListener(
            new AccessibleAdapter() {
              @Override
              public void getName(AccessibleEvent event) {
                event.result = "Desactivar MCP Live Sync";
              }

              @Override
              public void getDescription(AccessibleEvent event) {
                event.result = description;
              }
            });
    control
        .getAccessible()
        .addAccessibleControlListener(
            new AccessibleControlAdapter() {
              @Override
              public void getRole(AccessibleControlEvent event) {
                event.detail = ACC.ROLE_PUSHBUTTON;
              }
            });
    control.addDisposeListener(
        event -> {
          closed = true;
          control.getDisplay().timerExec(-1, tick);
          logo.dispose();
          disconnectedLogo.dispose();
        });
    try {
      updateTooltip(state);
      gui.getShell().layout(true, true);
      control.getDisplay().timerExec(100, tick);
    } catch (RuntimeException failure) {
      close();
      throw failure;
    }
  }

  private void frame() {
    if (closed || control.isDisposed()) return;
    var nextState = activity.get();
    boolean colorChanged = nextState.connected() != state.connected();
    state = nextState;
    boolean busy = state.connected() && state.working();
    long now = System.nanoTime();
    if (busy && !wasWorking) pulseStarted = now;
    int next = busy ? pulseAlpha(now - pulseStarted) : Math.min(255, alpha + 24);
    if (next != alpha || colorChanged) {
      alpha = next;
      control.redraw();
    }
    updateTooltip(state);
    wasWorking = busy;
    control.getDisplay().timerExec(busy || alpha < 255 ? 80 : 250, tick);
  }

  // Start solid and breathe over 2.4 seconds; remain legible even at the minimum opacity.
  static int pulseAlpha(long elapsedNanos) {
    double phase = Math.max(0, elapsedNanos) / 2_400_000_000d * Math.PI * 2;
    return (int) Math.round(205 + 50 * Math.cos(phase));
  }

  private void updateTooltip(HopAgentActivity state) {
    boolean processing = state.connected() && !state.operation().equals("En espera");
    String age =
        state.lastActivityMillis() == 0
            ? "Sin actividad"
            : "hace "
                + duration(Math.max(0, System.currentTimeMillis() - state.lastActivityMillis()));
    String text =
        "MCP Live Sync · "
            + (!state.connected() ? "Disponible" : processing ? "Procesando" : "Conectado")
            + "\nAgente: "
            + state.agent()
            + (state.version().isBlank() ? "" : " · " + state.version())
            + "\nOperación: "
            + state.operation()
            + "\n"
            + (processing ? "Tiempo transcurrido: " : "Duración última operación: ")
            + (state.lastActivityMillis() == 0 ? "—" : duration(state.elapsedMillis()))
            + "\nÚltima actividad: "
            + age;
    if (text.equals(description)) return;
    description = text;
    control.setToolTipText(web ? HopWebIndicatorTooltip.markup(description) : description);
  }

  private static String duration(long millis) {
    if (millis < 1000) return "menos de 1 s";
    long seconds = millis / 1000;
    return seconds < 60 ? seconds + " s" : seconds / 60 + " min " + seconds % 60 + " s";
  }

  void close(boolean restoreLayout) {
    if (closed) return;
    if (restoreLayout && !toolbar.isDisposed() && toolbarLayout.right == reservedRight) {
      toolbarLayout.right = previousRight;
    }
    control.dispose();
    if (restoreLayout && !toolbar.getShell().isDisposed()) toolbar.getShell().layout(true, true);
  }

  @Override
  public void close() {
    close(true);
  }
}
