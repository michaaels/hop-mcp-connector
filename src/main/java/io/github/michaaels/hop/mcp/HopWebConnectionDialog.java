package io.github.michaaels.hop.mcp;

import java.net.URI;
import org.apache.hop.ui.hopgui.HopGui;
import org.eclipse.rap.rwt.RWT;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/** Native connection information; activation itself enables the remote service. */
final class HopWebConnectionDialog {
  private final HopGui hopGui;
  private final HopRemoteMcpConnection connection;

  HopWebConnectionDialog(HopGui hopGui, HopRemoteMcpConnection connection) {
    this.hopGui = hopGui;
    this.connection = connection;
  }

  void open() {
    var shell = new Shell(hopGui.getShell(), SWT.DIALOG_TRIM | SWT.RESIZE);
    shell.setText(HopMcpGuiPlugin.DISPLAY_NAME);
    shell.setLayout(new GridLayout(1, false));
    var message = new Label(shell, SWT.WRAP);
    message.setText(
        "MCP Live Sync está disponible · Sesión "
            + connection.id().substring(0, 8)
            + ". Puedes indicarle esta URL al agente:\n"
            + "El agente puede consultar y editar el proyecto de esta sesión, sin ejecutar ETL.");
    var layout = new GridData(SWT.FILL, SWT.CENTER, true, false);
    layout.widthHint = 650;
    message.setLayoutData(layout);
    var endpoint = new Text(shell, SWT.BORDER | SWT.READ_ONLY);
    endpoint.setText(connection.webUrl());
    endpoint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    var copy = new Button(shell, SWT.PUSH);
    copy.setText("Copiar URL");
    copy.addListener(
        SWT.Selection,
        event -> {
          var clipboard = new Clipboard(shell.getDisplay());
          try {
            clipboard.setContents(
                new Object[] {connection.webUrl()}, new Transfer[] {TextTransfer.getInstance()});
          } finally {
            clipboard.dispose();
          }
        });
    var help = new Label(shell, SWT.WRAP);
    help.setText(
        "Gris: disponible sin agente. Negro: agente conectado. Pulsación: procesando.\n"
            + "El icono desconecta al agente y queda disponible para reconectar.\n"
            + "El menú MCP Live Sync desactiva el servicio por completo.");
    help.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    var close = new Button(shell, SWT.PUSH);
    close.setText("Volver al editor");
    close.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
    close.addListener(SWT.Selection, event -> shell.close());
    shell.pack();
    var parent = hopGui.getShell().getBounds();
    var size = shell.getSize();
    shell.setLocation(
        parent.x + Math.max(0, (parent.width - size.x) / 2),
        parent.y + Math.max(0, (parent.height - size.y) / 2));
    shell.open();
  }

  static URI publicUi(HopGui hopGui) {
    String configured = hopGui.getVariables().getVariable("HOP_MCP_PUBLIC_UI_URL");
    if (configured != null && !configured.isBlank()) return URI.create(configured);
    var request = RWT.getRequest();
    String scheme =
        request.isSecure() || !request.getServerName().equals("127.0.0.1") ? "https" : "http";
    return URI.create(
        scheme
            + "://"
            + request.getHeader("Host")
            + request.getContextPath()
            + request.getServletPath());
  }
}
