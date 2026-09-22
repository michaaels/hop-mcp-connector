package io.github.michaaels.hop.mcp;

import java.io.IOException;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.hop.core.gui.plugin.GuiPlugin;
import org.apache.hop.core.gui.plugin.menu.GuiMenuElement;
import org.apache.hop.ui.hopgui.HopGui;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.MessageBox;

/** Hop Desktop and Hop Web entry point for explicit, session-scoped MCP live synchronization. */
@GuiPlugin
public class HopMcpGuiPlugin {
  public static final String MENU_ID = "40250-menu-tools-hop-mcp-connector";

  private static final Map<HopGui, HopLiveUiSync> LIVE_SYNCS = new IdentityHashMap<>();

  @GuiMenuElement(
      root = HopGui.ID_MAIN_MENU,
      id = MENU_ID,
      label = "MCP Connector for Apache Hop live synchronization...",
      toolTip = "Start or stop live synchronization for semantic MCP changes",
      parentId = HopGui.ID_MAIN_MENU_TOOLS_PARENT_ID,
      separator = true)
  public void toggleLiveSynchronization() {
    HopGui hopGui = HopGui.getInstance();
    synchronized (HopMcpGuiPlugin.class) {
      HopLiveUiSync liveSync = LIVE_SYNCS.get(hopGui);
      if (liveSync != null && liveSync.isRunning()) {
        stopLiveSynchronization(hopGui, liveSync);
      } else {
        startLiveSynchronization(hopGui);
      }
    }
  }

  private static void startLiveSynchronization(HopGui hopGui) {
    try {
      Path projectRoot = determineProjectRoot(hopGui);
      HopLiveUiSync candidate =
          HopLiveUiSyncFactory.create(hopGui, projectRoot, () -> removeLiveSynchronization(hopGui));
      candidate.start();
      LIVE_SYNCS.put(hopGui, candidate);
      showInformation(
          hopGui,
          "MCP Connector for Apache Hop live synchronization",
          "Live synchronization for "
              + clientLabel(candidate)
              + " is running for:\n\n"
              + projectRoot
              + "\n\nSemantic MCP changes will open or refresh Hop definitions. "
              + "Tabs with unsaved changes are never overwritten.");
    } catch (Exception e) {
      hopGui
          .getLog()
          .logError("Unable to start MCP Connector for Apache Hop live synchronization", e);
      showError(
          hopGui,
          "MCP Connector for Apache Hop",
          "Live synchronization could not be started. See the Hop log for details.");
    }
  }

  private static void stopLiveSynchronization(HopGui hopGui, HopLiveUiSync liveSync) {
    try {
      liveSync.close();
      showInformation(
          hopGui,
          "MCP Connector for Apache Hop live synchronization",
          "Live synchronization for " + clientLabel(liveSync) + " has stopped.");
    } catch (IOException e) {
      hopGui
          .getLog()
          .logError("Unable to stop MCP Connector for Apache Hop live synchronization", e);
      showError(
          hopGui,
          "MCP Connector for Apache Hop",
          "Live synchronization could not be stopped cleanly. See the Hop log for details.");
    }
  }

  private static synchronized void removeLiveSynchronization(HopGui hopGui) {
    LIVE_SYNCS.remove(hopGui);
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
