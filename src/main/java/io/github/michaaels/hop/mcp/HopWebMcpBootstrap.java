package io.github.michaaels.hop.mcp;

import org.apache.hop.core.extension.ExtensionPoint;
import org.apache.hop.core.extension.IExtensionPoint;
import org.apache.hop.core.logging.ILogChannel;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.ui.hopgui.HopGui;
import org.apache.hop.ui.util.EnvironmentUtils;

/** Register an inactive endpoint so agents can report Live Sync state before it is enabled. */
@ExtensionPoint(
    id = "HopWebMcpBootstrap",
    extensionPointId = "HopGuiStart",
    description = "Expose Hop Web MCP availability without enabling project access")
public class HopWebMcpBootstrap implements IExtensionPoint<HopGui> {
  @Override
  public void callExtensionPoint(ILogChannel log, IVariables variables, HopGui hopGui) {
    if (EnvironmentUtils.getInstance().isWeb()) HopWebRemoteEndpoint.current();
  }
}
