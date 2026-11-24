# MCP Connector for Apache Hop

## 2.6.0 development candidate

- Use MCP Java SDK 2.2.0 with protocol revision 2025-11-25.

- **MCP Connect** offers **Live Sync** and **Result Only** for native authoring. **MCP Disconnect** turns off the service in the window.
- **Connect Agent** pairs one agent with a Web window using a two-minute, single-use link. The UUID belongs to the active connection and disappears when the agent disconnects.
- Unpaired requests receive a single instruction to copy a link. Conflict notices show only the affected file, and Web editor acknowledgements remain scoped to the requesting window.
- Update the Web plugin and the agent's remote profile together. See the [2.6.0 handoff](docs/handoff-mcp-2.6.0.md).

## Previous version 2.4.0

Version 2.4.0 connects your MCP client to Apache Hop through local STDIO, Live Sync in Hop Desktop, or remote Live Sync in Hop Web. It targets Apache Hop 2.20.x exclusively and requires Java 21. The MCP Java SDK is 2.1.0; the protocol baseline remains 2025-11-25.

### Get started

Download `hop-mcp-connector-2.4.0.zip`, stop Hop/MCP (or Tomcat for Web), and extract it into the Hop installation root. Restart Hop and configure your MCP client.

- **Local MCP:** `hop mcp --root <project>` starts project inspection. Add `--allow-mutation` to create and edit flows, or `--allow-execution` for local execution.
- **Desktop Live Sync:** activate **Tools → MCP Live Sync** in Hop Desktop and connect the local MCP to that same project. Follow guided changes in the native editor.
- **Web Live Sync:** activate Live Sync in the Hop Web session, then connect the agent using `hop mcp --remote-ui` and the site's URL. No shared project directory is required. Web activation permits inspection and editing; ETL execution and deep checks stay disabled.

See the [README](https://github.com/michaaels/hop-mcp-connector#readme) for an illustrative animation and simple setup, and the [Web guide](https://github.com/michaaels/hop-mcp-connector/blob/main/docs/remote-hop-web.md) for HTTPS and session selection.

### Changes

- Introduce a concise README organized around local MCP, Desktop Live Sync and Web Live Sync, with a lightweight illustrative GIF, a static reduced-motion alternative, example requests and installation steps. Retain detailed technical guidance in `docs/reference.md`.

- Redact secrets across bounded native configuration alias chains and named-variable metadata rows. Accept native nested metadata through compact, depth-bounded output schemas, with service and STDIO regressions. Extend ordinary Hop 2.20 tests for SSH timeouts, Calculator legacy defaults, MetaInject migration and completion callbacks; the added native dependencies are test-only.

- Connect agents to Hop Web by URL through an explicitly enabled MCP Live Sync session. The `--remote-ui` STDIO profile exposes four lightweight tools and uses RAP's existing servlet without requiring a shared project directory. Preserve HTTPS, Host/Origin validation, bounded requests and concurrency, session binding and shutdown gating. Web activation permits project inspection and semantic editing; ETL execution, deep checks and proxy web tools remain disabled.
- Separate Web service availability from agent presence. The compact native MCP indicator is grey when available, black when connected, and pulses during activity. Show agent, operation and timing details. Clicking the Web icon disconnects the agent; the menu stops the service. Multiple sessions return bounded project/open-file choices that require explicit user selection.
- Present guided changes in native Desktop and Hop Web/RAP editors with asynchronous blocks, pacing, pause and instant application. Preserve dirty-tab protection and route presentation events to their owning UI.
- Add semantic canvas layout and named-parameter authoring, improve native field injection and routing diagnostics, and preserve preview, SHA-256 preconditions, protected backups, native reload validation and rollback.
- Compact component output schemas, separate schema-discovery limits from mutation limits, protect STDIO from cached Log4j console appenders, and resolve definition references in the native file context while retaining path confinement and response bounds.

### Verification and scope

The ordinary `mvn -B clean verify` build now targets Hop 2.20.0 and includes native component and JDBC tests without a snapshot compatibility profile. CI is configured for Linux verification, Windows junction security, checksum-verified clean Hop 2.20.0 installation and native acceptance, and the frozen MCP 2025-11-25 conformance regression baseline. A configured workflow is not evidence that a particular run passed.

The dated reports in `docs/` describe the local UI and regression cases exercised for this version and their remaining limitations. The conformance baseline retains 25 everything-server fixture failures; it does not certify complete everything-server conformance or every behavior of the separate RAP endpoint.

The tag-controlled release workflow validates the verified Marketplace ZIP and Jandex index, retains CycloneDX JSON/XML SBOMs, creates SHA-256 checksums, and produces archive-provenance and SBOM attestations before publishing. Version coordinates are `2.4.0`; the release tag is `v2.4.0`.
