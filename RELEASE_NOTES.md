# MCP Connector for Apache Hop 2.0.0 (unreleased)

This draft describes the current development branch. It is not a published release.

- The Maven and Marketplace artifact is `io.github.michaaels:hop-mcp-connector`; the ZIP is named `hop-mcp-connector-${version}.zip`.
- The displayed product name is **MCP Connector for Apache Hop**, an independent community project.
- The MCP Java SDK remains 2.0.1 and the declared MCP protocol baseline is 2025-11-25 over STDIO.
- Deep-check, execution, mutation/authoring, and Hop Web tools are hidden until their matching server-side opt-in is enabled.
- Tool annotations are supplied as client hints; authorization remains server-side.
- Applied mutation continues to use native Hop objects and the existing transactional safeguards.

Strict bounded output schemas are implemented for eleven tools, and the STDIO integration test validates each schema against actual tool output. It exercises native component-schema discovery, synchronous pipeline execution, and asynchronous execution status through Apache Hop's local engine with execution explicitly enabled. Schemas for the remaining tools are pending. A clean Hop 2.19.0 installation from the Marketplace ZIP passed locally and is added to CI; install through a published Hop Marketplace repository remains untested. The official MCP Conformance Suite has not passed or been run. Do not publish this release until these gaps are closed or their disposition is explicitly accepted.
