package io.github.michaaels.hop.mcp;

import java.io.IOException;
import java.nio.file.Path;
import org.apache.hop.ui.hopgui.HopGui;
import org.apache.hop.ui.hopgui.ServerPushSessionFacade;

/** Owns one explicitly enabled RAP session and its remote MCP service. */
final class HopWebLiveSync extends HopLiveUiSync {
  private final HopGui hopGui;
  private final String projectName;
  private HopRemoteMcpConnection remote;

  HopWebLiveSync(HopGui hopGui, Path projectRoot, Runnable closedCallback) throws IOException {
    super(hopGui, projectRoot, "web", closedCallback);
    this.hopGui = hopGui;
    projectName =
        hopGui.getVariables().getVariable("HOP_PROJECT_NAME", projectRoot.getFileName().toString());
  }

  void showConnectionDialog() {
    new HopWebConnectionDialog(hopGui, remote).open();
  }

  @Override
  protected boolean requestsInProgress() {
    return remote != null && remote.isWorking();
  }

  @Override
  protected HopAgentActivity indicatorActivity() {
    if (remote == null) return HopAgentActivity.disconnected();
    var state = remote.activity();
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
    remote.editor(projectName, file);
    if (state.connected() && state.operation().equals("En espera") && isPresenting())
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

  @Override
  protected void disconnectAgent() {
    if (remote != null) remote.disconnectAgent();
    clearAgentDrafts();
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
      remote = HopRemoteMcpConnection.open(service, HopWebConnectionDialog.publicUi(hopGui));
      remote.editor(projectName, "");
      HopWebRemoteEndpoint.current().add(remote);
    } catch (Exception failure) {
      if (remote != null) remote.close();
      ServerPushSessionFacade.stop();
      throw new IllegalStateException("Unable to enable remote MCP for this Web session", failure);
    }
  }

  @Override
  protected void stopUiDispatch() {
    if (remote != null) remote.close();
    ServerPushSessionFacade.stop();
  }
}
