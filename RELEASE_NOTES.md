# MCP Connector for Apache Hop 2.0.0

Released on 2026-09-22.

- The Maven and Marketplace artifact is `io.github.michaaels:hop-mcp-connector`; the ZIP is named `hop-mcp-connector-${version}.zip`.
- The displayed product name is **MCP Connector for Apache Hop**, an independent community project.
- The MCP Java SDK remains 2.0.1 and the declared MCP protocol baseline is 2025-11-25 over STDIO.
- Deep-check, execution, mutation/authoring, and Hop Web tools are hidden until their matching server-side opt-in is enabled.
- Tool annotations are supplied as client hints; authorization remains server-side.
- Applied mutation continues to use native Hop objects and the existing transactional safeguards.

Strict bounded output schemas are implemented for eleven tools, and the STDIO integration test validates each schema against actual tool output. It exercises native component-schema discovery, synchronous pipeline execution, and asynchronous execution status through Apache Hop's local engine with execution explicitly enabled. Other tools retain bounded JSON-compatible results but do not yet advertise an output schema. A clean Hop 2.19.0 installation from the Marketplace ZIP passes in CI, and the Hop 2.20.0-SNAPSHOT compatibility profile also passes. Installation through the published Marketplace catalog should be verified after publication. The official MCP Conformance Suite was not run for this release.
