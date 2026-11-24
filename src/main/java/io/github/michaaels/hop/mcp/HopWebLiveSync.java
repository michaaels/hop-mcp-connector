package io.github.michaaels.hop.mcp;

import java.io.IOException;
import java.nio.file.Path;
import org.apache.hop.ui.hopgui.HopGui;
import org.apache.hop.ui.hopgui.ServerPushSessionFacade;
import org.eclipse.swt.widgets.Shell;

/** Owns one explicitly enabled RAP session and its remote MCP service. */
final class HopWebLiveSync extends HopLiveUiSync {
  private final HopGui hopGui;
  private final String projectName;
  private HopRemoteMcpConnection remote;
  private Shell details;
  private Shell conflictDialog;
  private String addressId = "";

  HopWebLiveSync(HopGui hopGui, Path projectRoot, Runnable closedCallback) throws IOException {
    super(hopGui, projectRoot, "web", closedCallback);
    this.hopGui = hopGui;
    projectName =
        hopGui.getVariables().getVariable("HOP_PROJECT_NAME", projectRoot.getFileName().toString());
  }

  @Override
  void showSessionDetails() {
    if (remote == null) return;
    if (details != null && !details.isDisposed()) {
      details.forceActive();
      return;
    }
    details =
        HopMcpSessionDialog.details(
            hopGui,
            this::indicatorActivity,
            this::indicatorSessionId,
            remote::createPairingLink,
            this::disconnectAgent);
  }

  @Override
  protected String indicatorSessionId() {
    return remote == null ? "" : remote.id();
  }

  @Override
  protected boolean requestsInProgress() {
    return remote != null && remote.isWorking();
  }

  @Override
  protected HopAgentActivity indicatorActivity() {
    if (remote == null) return HopAgentActivity.disconnected();
    var state = remote.activity();
    updateAddress(state.connected() ? remote.id() : "");
    if (!state.connected()) {
      closeConflictDialog();
      remote.editorFile("");
      return state;
    }
    if (conflictDialog == null || conflictDialog.isDisposed()) {
      var conflict = remote.takeConflict();
      if (conflict != null) conflictDialog = HopMcpSessionDialog.conflict(hopGui, conflict);
    }
    var handler =
        org.apache.hop.ui.hopgui.perspective.explorer.ExplorerPerspective.getInstance()
            .getActiveFileTypeHandler();
    String file = "";
    if (handler != null && handler.getFilename() != null) {
      try {
        var path =
            Path.of(hopGui.getVariables().resolve(handler.getFilename()))
                .toAbsolutePath()
                .normalize();
        if (path.startsWith(projectRoot()))
          file = projectRoot().relativize(path).toString().replace('\\', '/');
      } catch (RuntimeException ignored) {
        /* Non-local handlers do not identify a project file. */
      }
    }
    if (file.startsWith(HopLiveUiEventBroker.CONTROL_DIRECTORY + "/")) file = "";
    remote.editorFile(file);
    if (state.operation().equals("En espera") && isPresenting())
      return new HopAgentActivity(
          true,
          true,
          state.agent(),
          state.version(),
          "Presentando cambios",
          Math.max(0, System.currentTimeMillis() - state.lastActivityMillis()),
          state.lastActivityMillis());
    return state;
  }

  @Override
  protected boolean receiveAgentEvents() {
    return remote != null && remote.activity().connected();
  }

  @Override
  protected long eventEpoch() {
    return remote == null ? 0 : remote.connectedSince();
  }

  private void disconnectAgent() {
    if (remote != null) {
      remote.disconnectAgent();
      remote.editorFile("");
    }
    updateAddress("");
    closeConflictDialog();
  }

  private void updateAddress(String id) {
    if (id.equals(addressId)) return;
    if (!addressId.isEmpty()) clearAgentDrafts();
    if (id.isEmpty()) HopWebSessionUrl.remove();
    else HopWebSessionUrl.install(id);
    addressId = id;
  }

  private void closeConflictDialog() {
    if (conflictDialog != null && !conflictDialog.isDisposed()) conflictDialog.close();
    conflictDialog = null;
  }

  @Override
  protected void startUiDispatch() {
    ServerPushSessionFacade.start();
    try {
      var service =
          new HopMcpService(
              new ProjectFiles(projectRoot()),
              hopGui.getVariables(),
              hopGui.getMetadataProvider(),
              false,
              false,
              true,
              false,
              null,
              agentEvents());
      service.webPresentation(presentationMode(), projectName);
      remote = HopRemoteMcpConnection.open(service, HopWebSessionUrl.publicUi(hopGui));
      HopWebRemoteEndpoint.current().add(remote);
      HopWebSessionUrl.remove();
    } catch (Exception failure) {
      if (remote != null) remote.close();
      ServerPushSessionFacade.stop();
      throw new IllegalStateException("Unable to enable remote MCP for this Web session", failure);
    }
  }

  @Override
  protected void stopUiDispatch() {
    if (remote != null) remote.close();
    if (!hopGui.getShell().isDisposed()) {
      if (details != null && !details.isDisposed()) details.close();
      closeConflictDialog();
      HopWebSessionUrl.remove();
    }
    ServerPushSessionFacade.stop();
  }
}
