package io.github.michaaels.hop.mcp;

import java.net.URI;
import java.util.UUID;
import org.apache.hop.ui.hopgui.HopGui;
import org.eclipse.rap.rwt.RWT;
import org.eclipse.rap.rwt.client.service.JavaScriptExecutor;

/** Keeps the MCP selector in this document while Hop's RAP updater changes project or file. */
final class HopWebSessionUrl {
  private HopWebSessionUrl() {}

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

  static void install(String session) {
    String id = UUID.fromString(session).toString();
    execute(
        """
        (() => {
          window.__hopMcpUrlCleanup?.();
          const replace = history.replaceState;
          const id = '%s', path = location.pathname;
          let active = true;
          function update(state, title, url) {
            if (!active || url == null) return replace.apply(this, arguments);
            const next = new URL(url, location.href);
            if (next.origin !== location.origin || next.pathname !== path) {
              return replace.apply(this, arguments);
            }
            next.searchParams.set('session', id);
            return replace.call(this, state, title, next);
          }
          history.replaceState = update;
          update.call(history, history.state, '', location.href);
          window.__hopMcpUrlCleanup = () => {
            active = false;
            if (history.replaceState === update) history.replaceState = replace;
            const current = new URL(location.href);
            if (current.searchParams.get('session') === id) {
              current.searchParams.delete('session');
              replace.call(history, history.state, '', current);
            }
            delete window.__hopMcpUrlCleanup;
          };
        })();
        """
            .formatted(id));
  }

  static void remove() {
    execute(
        """
        (() => {
          window.__hopMcpUrlCleanup?.();
          const current = new URL(location.href);
          if (current.searchParams.has('session')) {
            current.searchParams.delete('session');
            history.replaceState(history.state, '', current);
          }
        })();
        """);
  }

  private static void execute(String script) {
    var executor = RWT.getClient().getService(JavaScriptExecutor.class);
    if (executor != null) executor.execute(script);
  }
}
