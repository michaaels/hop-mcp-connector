package io.github.michaaels.hop.mcp;

import org.eclipse.rap.rwt.RWT;
import org.eclipse.rap.rwt.client.service.JavaScriptExecutor;
import org.eclipse.swt.widgets.Control;

/** Keep this native RAP tooltip informational even when viewport clamping overlaps its control. */
final class HopWebIndicatorTooltip {
  private HopWebIndicatorTooltip() {}

  static void enable(Control control) {
    control.setData(RWT.TOOLTIP_MARKUP_ENABLED, true);
    RWT.getClient()
        .getService(JavaScriptExecutor.class)
        .execute(
            """
        if (!document.getElementById('mcp-live-tooltip-style')) {
          const style = document.createElement('style');
          style.id = 'mcp-live-tooltip-style';
          style.textContent = 'div:has(> div > div > span.mcp-live-tooltip):not(:has(canvas)) { pointer-events: none; }';
          document.head.appendChild(style);
        }
        """);
  }

  static String markup(String text) {
    return "<span class=\"mcp-live-tooltip\">"
        + text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br/>")
        + "</span>";
  }
}
