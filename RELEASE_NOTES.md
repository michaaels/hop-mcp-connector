# MCP Connector for Apache Hop 2.6.0

Apache Hop 2.20.x · Java 21 · MCP Java SDK 2.2.0 · MCP protocol 2025-11-25

## What's new

- **Choose how changes appear in Hop.** **MCP Connect** offers **Live Sync** to follow guided construction and **Result Only** to show the completed flow. **MCP Disconnect** stops the service for that window.
- **Pair the agent with the intended Hop Web window.** **Connect Agent** copies a single-use link that expires after two minutes. The visible connection UUID is separate from the agent credential. Disconnecting revokes the binding and removes the UUID.
- **Keep Web activity scoped to its window.** Unpaired requests receive the same pairing instruction. Edit-conflict notices identify the affected file, and editor acknowledgements stay scoped to the requesting window.

## Install or upgrade

Close Hop and the MCP process, or stop Tomcat for Hop Web. Replace the previous plugin with `hop-mcp-connector-2.6.0.zip` extracted at the Hop installation root. For Web, update the server plugin and the plugin used by the agent's `--remote-ui` profile together. Restart Hop or Tomcat, then reconnect the agent with a new link copied from the intended window.

[Quick start](https://github.com/michaaels/hop-mcp-connector/blob/v2.6.0/README.md) · [Español](https://github.com/michaaels/hop-mcp-connector/blob/v2.6.0/README.es.md) · [Web connection guide](https://github.com/michaaels/hop-mcp-connector/blob/v2.6.0/docs/remote-hop-web.md) · [Previous release 2.4.0](https://github.com/michaaels/hop-mcp-connector/releases/tag/v2.4.0)

The release includes the plugin ZIP, CycloneDX SBOMs in JSON and XML, SHA-256 checksums, and GitHub attestations for the ZIP and its SBOM.
