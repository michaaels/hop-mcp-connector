package io.github.michaaels.hop.mcp;

import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.hop.ui.core.PropsUi;
import org.apache.hop.ui.core.gui.GuiResource;
import org.apache.hop.ui.hopgui.HopGui;
import org.apache.hop.ui.util.EnvironmentUtils;
import org.eclipse.rap.rwt.RWT;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

/** Native activation and connection details for one Hop GUI. */
final class HopMcpSessionDialog {
  static final String LIVE = "guided";
  static final String RESULT_ONLY = "result_only";
  private static final String PREFERENCE = "hop.mcp.presentation";
  private static final String COPY_LABEL = "Copiar enlace de conexión con agente";

  private HopMcpSessionDialog() {}

  static void chooseMode(HopGui gui, Consumer<String> start) {
    boolean web = EnvironmentUtils.getInstance().isWeb();
    String remembered =
        web
            ? RWT.getSettingStore().getAttribute(PREFERENCE)
            : PropsUi.getInstance().getCustomParameter(PREFERENCE, LIVE);
    var shell = shell(gui, HopMcpGuiPlugin.DISPLAY_NAME);
    label(shell, "How do you want to see MCP changes?");
    var live = new Button(shell, SWT.RADIO);
    live.setText("Live Sync");
    label(shell, "Watch the editor update in blocks. Pause or skip to the finished flow.");
    var result = new Button(shell, SWT.RADIO);
    result.setText("Result Only");
    label(shell, "Show the main flow at the end. Find the details in the chat.");
    result.setSelection(RESULT_ONLY.equals(remembered));
    live.setSelection(!result.getSelection());
    var connect = new Button(shell, SWT.PUSH);
    connect.setText("Connect");
    shell.setDefaultButton(connect);
    connect.addListener(
        SWT.Selection,
        event -> {
          String mode = result.getSelection() ? RESULT_ONLY : LIVE;
          try {
            if (web) RWT.getSettingStore().setAttribute(PREFERENCE, mode);
            else PropsUi.getInstance().setCustomParameter(PREFERENCE, mode);
          } catch (IOException failure) {
            gui.getLog().logError("Unable to remember the MCP presentation preference", failure);
          }
          shell.close();
          start.accept(mode);
        });
    open(gui, shell);
  }

  static Shell details(
      HopGui gui,
      Supplier<HopAgentActivity> activity,
      Supplier<String> sessionId,
      Supplier<String> createPairingLink,
      Runnable disconnect) {
    var shell = shell(gui, "MCP");
    var details = label(shell, "");
    var action = new Button(shell, SWT.PUSH);
    var feedback = label(shell, "");
    Runnable refresh =
        () -> {
          var state = activity.get();
          details.setText(
              HopLiveIndicator.describe(state, state.connected() ? sessionId.get() : ""));
          String text = state.connected() ? "Disconnect Agent" : "Connect Agent";
          if (text.equals(action.getText())) return;
          action.setText(text);
          action.setImage(state.connected() ? null : GuiResource.getInstance().getImageCopyHop());
          action.setToolTipText(state.connected() ? null : COPY_LABEL);
          action.setForeground(
              state.connected() ? shell.getDisplay().getSystemColor(SWT.COLOR_DARK_RED) : null);
          feedback.setText("");
          shell.layout(true, true);
        };
    action.addListener(
        SWT.Selection,
        event -> {
          if ("Disconnect Agent".equals(action.getText())) {
            disconnect.run();
            refresh.run();
            return;
          }
          String link = createPairingLink.get();
          if (link == null) {
            refresh.run();
            if ("Connect Agent".equals(action.getText())) {
              feedback.setText("Espera a que termine la operación anterior.");
            }
            return;
          }
          var clipboard = new Clipboard(shell.getDisplay());
          try {
            clipboard.setContents(new Object[] {link}, new Transfer[] {TextTransfer.getInstance()});
            feedback.setText("Enlace copiado. Válido por dos minutos.");
          } catch (RuntimeException | org.eclipse.swt.SWTError failure) {
            feedback.setText("No se pudo copiar el enlace");
          } finally {
            clipboard.dispose();
          }
        });
    refresh.run();
    open(gui, shell);
    shell
        .getDisplay()
        .timerExec(
            500,
            new Runnable() {
              @Override
              public void run() {
                if (shell.isDisposed()) return;
                refresh.run();
                shell.getDisplay().timerExec(500, this);
              }
            });
    return shell;
  }

  static Shell conflict(HopGui gui, HopWebProjectWork.ConflictNotice notice) {
    var shell = shell(gui, "Conflicto de edición MCP");
    label(
        shell,
        "Otra sesión está editando este archivo:"
            + "\n\n"
            + notice.path()
            + "\n\nTu cambio no se aplicó.");
    var close = new Button(shell, SWT.PUSH);
    close.setText("Cerrar");
    close.addListener(SWT.Selection, event -> shell.close());
    shell.setDefaultButton(close);
    open(gui, shell);
    return shell;
  }

  private static Shell shell(HopGui gui, String title) {
    var shell = new Shell(gui.getShell(), SWT.DIALOG_TRIM);
    shell.setText(title);
    shell.setLayout(new GridLayout(1, false));
    shell.addListener(
        SWT.Traverse,
        event -> {
          if (event.detail == SWT.TRAVERSE_ESCAPE) shell.close();
        });
    return shell;
  }

  private static Label label(Composite parent, String text) {
    var label = new Label(parent, SWT.WRAP);
    label.setText(text);
    var layout = new GridData(SWT.FILL, SWT.CENTER, true, false);
    layout.widthHint = 410;
    label.setLayoutData(layout);
    return label;
  }

  private static void open(HopGui gui, Shell shell) {
    shell.pack();
    var parent = gui.getShell().getBounds();
    var size = shell.getSize();
    shell.setLocation(
        parent.x + Math.max(0, (parent.width - size.x) / 2),
        parent.y + Math.max(0, (parent.height - size.y) / 2));
    shell.open();
  }
}
