# Apache Hop MCP 1.0.0 — Native semantic MCP

Apache Hop MCP 1.0.0 is the first stable release of the native-Java semantic MCP for Apache Hop.

- Runs directly in the Apache Hop JVM on Java 21 with MCP Java SDK 2.0.1 over STDIO.
- Inspects projects, definitions, components, dependencies, lineage and plugin metadata with bounded results and secret redaction.
- Validates definitions and optionally runs Apache Hop deep checks and local execution through independent, explicit authorization flags.
- Authors pipelines and workflows through native Hop semantic objects and the plugin registry rather than arbitrary XML replacement.
- Supports reviewable, SHA-256-bound correction plans and transactional mutation with preview, backup, atomic replacement, native reload validation and rollback.
- Supports optional Desktop and Hop Web live synchronization while protecting dirty tabs and keeping the bridge project-local.
- Protects the full MCP initialization handshake and consecutive tool calls with an in-process STDIO integration test.
- Preserves project-root confinement, hardened XML parsing, bounded execution and traversal, and sensitive-data redaction.
- Builds against Apache Hop 2.19.0 and is compatibility-tested against the current Apache Hop 2.20.0-SNAPSHOT line.
- Publishes the Marketplace artifact as `apache-hop-mcp-1.0.0.zip` under GitHub tag `v1.0.0` after all release gates pass.

This is a community project, not an official Apache Software Foundation release.
