# Apache Hop MCP 0.8.0 — Gated test and diagnostics cycle

This release adds a single semantic test cycle for Apache Hop pipelines and workflows while preserving explicit authorization boundaries and human-reviewed correction.

- Adds `hop_test_definition` to run structural validation first, an optional native deep check second, and optional local execution last.
- Stops the cycle before execution when structural validation or a requested deep check fails.
- Normalizes validation, check, execution and log findings into bounded machine-readable diagnostics.
- Returns phase status, a final pass/fail result, diagnostic codes, severity and redacted messages.
- Produces advisory correction candidates that point clients to relevant semantic tools or mutation operations.
- Never applies suggested corrections automatically; mutation still requires preview, explicit `apply=true`, authorization and SHA-256 preconditions.
- Keeps native deep checking behind `--allow-deep-check` because it may access configured external systems.
- Keeps local execution behind `--allow-execution`, with bounded concurrency, timeouts and redacted logs.
- Preserves project-root confinement, hardened XML parsing, bounded results and secret redaction.
- Runs directly in the Apache Hop JVM on Java 21 with MCP Java SDK 2.0.1 over STDIO.
- Built against Apache Hop 2.19.0 and compatibility-tested against the current Apache Hop 2.20.0-SNAPSHOT line.
- GitHub Actions runs `mvn -B clean verify` and validates the Marketplace ZIP before creating `v0.8.0`.

This is a community project, not an official Apache Software Foundation release.
