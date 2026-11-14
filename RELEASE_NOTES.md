# MCP Connector for Apache Hop 2.4.0

Version 2.4.0 targets Apache Hop 2.20.x exclusively, compiling against Hop 2.20.0 with Java 21 and MCP Java SDK 2.1.0. The MCP protocol baseline remains 2025-11-25. This source update does not itself publish a GitHub Release or downloadable release assets.

## Changes

- Connect agents to Hop Web by URL through an explicitly enabled MCP Live Sync session. The `--remote-ui` STDIO profile exposes four lightweight tools and uses RAP's existing servlet without requiring a shared project directory. Preserve HTTPS, Host/Origin validation, bounded requests and concurrency, session binding and shutdown gating. Web activation permits project inspection and semantic editing; ETL execution, deep checks and proxy web tools remain disabled.
- Separate Web service availability from agent presence. The compact native MCP indicator is grey when available, black when connected, and pulses during activity. Show agent, operation and timing details. Clicking the Web icon disconnects the agent; the menu stops the service. Multiple sessions return bounded project/open-file choices that require explicit user selection.
- Present guided changes in native Desktop and Hop Web/RAP editors with asynchronous blocks, pacing, pause and instant application. Preserve dirty-tab protection and route presentation events to their owning UI.
- Add semantic canvas layout and named-parameter authoring, improve native field injection and routing diagnostics, and preserve preview, SHA-256 preconditions, protected backups, native reload validation and rollback.
- Compact component output schemas, separate schema-discovery limits from mutation limits, protect STDIO from cached Log4j console appenders, and resolve definition references in the native file context while retaining path confinement and response bounds.

## Verification and scope

The ordinary `mvn -B clean verify` build now targets Hop 2.20.0 and includes native component and JDBC tests without a snapshot compatibility profile. CI is configured for Linux verification, Windows junction security, checksum-verified clean Hop 2.20.0 installation and native acceptance, and the frozen MCP 2025-11-25 conformance regression baseline. A configured workflow is not evidence that a particular run passed.

The dated reports in `docs/` describe the local UI and regression cases exercised for this version and their remaining limitations. The conformance baseline retains 25 everything-server fixture failures; it does not certify complete everything-server conformance or every behavior of the separate RAP endpoint.

The tag-controlled release workflow validates the verified Marketplace ZIP and Jandex index, retains CycloneDX JSON/XML SBOMs, creates SHA-256 checksums, and produces archive-provenance and SBOM attestations before publishing. Version coordinates are `2.4.0`; the corresponding release tag is `v2.4.0` when a release is requested.
