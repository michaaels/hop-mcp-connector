package io.github.michaaels.hop.mcp;

import java.io.IOException;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.hop.core.extension.ExtensionPoint;
import org.apache.hop.core.extension.IExtensionPoint;
import org.apache.hop.core.gui.plugin.GuiPlugin;
import org.apache.hop.core.gui.plugin.menu.GuiMenuElement;
import org.apache.hop.core.logging.ILogChannel;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.ui.hopgui.HopGui;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.MessageBox;

/** Hop Desktop and Hop Web entry point for explicit, session-scoped MCP live synchronization. */
@GuiPlugin
@ExtensionPoint(
    id = "HopMcpMenuState",
    extensionPointId = "HopGuiStart",
    description = "Initialize the MCP actions for this Hop GUI session")
public class HopMcpGuiPlugin implements IExtensionPoint<HopGui> {
  public static final String MENU_ID = "40250-menu-tools-hop-mcp-connector";
  static final String DISCONNECT_MENU_ID = "40251-menu-tools-hop-mcp-disconnect";
  public static final String DISPLAY_NAME = "MCP Connect…";

  private static final Map<HopGui, HopLiveUiSync> LIVE_SYNCS = new IdentityHashMap<>();

  @GuiMenuElement(
      root = HopGui.ID_MAIN_MENU,
      id = MENU_ID,
      label = DISPLAY_NAME,
      toolTip = "Choose how to present MCP changes and enable the session",
      parentId = HopGui.ID_MAIN_MENU_TOOLS_PARENT_ID,
      separator = true)
  public void toggleLiveSynchronization() {
    HopGui hopGui = HopGui.getInstance();
    synchronized (HopMcpGuiPlugin.class) {
      HopLiveUiSync liveSync = LIVE_SYNCS.get(hopGui);
      if (liveSync != null && liveSync.isRunning()) return;
    }
    HopMcpSessionDialog.chooseMode(hopGui, mode -> startLiveSynchronization(hopGui, mode));
  }

  @GuiMenuElement(
      root = HopGui.ID_MAIN_MENU,
      id = DISCONNECT_MENU_ID,
      label = "MCP Disconnect",
      toolTip = "Disable the MCP service for this Hop session",
      parentId = HopGui.ID_MAIN_MENU_TOOLS_PARENT_ID,
      separator = true)
  public void disconnectLiveSynchronization() {
    HopGui hopGui = HopGui.getInstance();
    HopLiveUiSync liveSync;
    synchronized (HopMcpGuiPlugin.class) {
      liveSync = LIVE_SYNCS.get(hopGui);
    }
    if (liveSync != null && liveSync.isRunning()) stopLiveSynchronization(hopGui, liveSync);
  }

  @Override
  public void callExtensionPoint(ILogChannel log, IVariables variables, HopGui hopGui) {
    refreshMenu(hopGui, false);
  }

  private static synchronized void startLiveSynchronization(HopGui hopGui, String mode) {
    if (LIVE_SYNCS.containsKey(hopGui)) return;
    try {
      Path projectRoot = determineProjectRoot(hopGui);
      HopLiveUiSync candidate =
          HopLiveUiSyncFactory.create(hopGui, projectRoot, () -> removeLiveSynchronization(hopGui));
      candidate.presentationMode(mode);
      candidate.start();
      LIVE_SYNCS.put(hopGui, candidate);
      refreshMenu(hopGui, true);
      candidate.showSessionDetails();
    } catch (Exception e) {
      hopGui.getLog().logError("Unable to start MCP Live Sync", e);
      showError(
          hopGui,
          DISPLAY_NAME,
          "Live synchronization could not be started. See the Hop log for details.");
    }
  }

  private static void stopLiveSynchronization(HopGui hopGui, HopLiveUiSync liveSync) {
    try {
      liveSync.close();
      showInformation(
          hopGui,
          DISPLAY_NAME,
          "Live synchronization for " + clientLabel(liveSync) + " has stopped.");
    } catch (IOException e) {
      hopGui.getLog().logError("Unable to stop MCP Live Sync", e);
      showError(
          hopGui,
          DISPLAY_NAME,
          "Live synchronization could not be stopped cleanly. See the Hop log for details.");
    } finally {
      if (!hopGui.getShell().isDisposed()) refreshMenu(hopGui, false);
    }
  }

  private static synchronized void removeLiveSynchronization(HopGui hopGui) {
    LIVE_SYNCS.remove(hopGui);
  }

  private static void refreshMenu(HopGui hopGui, boolean connected) {
    var menu = hopGui.getMainMenuWidgets();
    menu.enableMenuItem(MENU_ID, !connected);
    menu.enableMenuItem(DISCONNECT_MENU_ID, connected);
    menu.setMenuItemVisible(MENU_ID, !connected);
    menu.setMenuItemVisible(DISCONNECT_MENU_ID, connected);
  }

  private static String clientLabel(HopLiveUiSync liveSync) {
    return "web".equals(liveSync.clientType()) ? "Hop Web" : "Hop Desktop";
  }

  private static Path determineProjectRoot(HopGui hopGui) {
    String configured = hopGui.getVariables().getVariable("PROJECT_HOME");
    if (StringUtils.isBlank(configured)) {
      configured = System.getProperty("user.dir");
    } else {
      configured = hopGui.getVariables().resolve(configured);
    }
    return Path.of(configured).toAbsolutePath().normalize();
  }

  private static void showInformation(HopGui hopGui, String title, String message) {
    MessageBox box = new MessageBox(hopGui.getShell(), SWT.OK | SWT.ICON_INFORMATION);
    box.setText(title);
    box.setMessage(message);
    box.open();
  }

  private static void showError(HopGui hopGui, String title, String message) {
    MessageBox box = new MessageBox(hopGui.getShell(), SWT.OK | SWT.ICON_ERROR);
    box.setText(title);
    box.setMessage(message);
    box.open();
  }
}
