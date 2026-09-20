# Apache Hop MCP 0.9.0 — Semantic correction plans

This release adds an explicit, reviewable correction-plan lifecycle on top of Apache Hop's native transactional mutation layer.

- Adds `hop_prepare_correction_plan` to validate native semantic operations and retain an immutable preview without writing files.
- Adds `hop_apply_correction_plan` to explicitly apply the exact prepared plan through the existing native semantic mutator.
- Adds `hop_correction_plan_status` for bounded session-local audit events and plan state.
- Binds every plan to a plan SHA-256 and to the definition SHA-256 observed during preparation.
- Rejects altered plan digests and definitions changed after preview.
- Makes plans single-use, limits retention to 100 plans per MCP session, and expires them after one hour.
- Consumes failed applications so a new preview is required before retrying.
- Keeps plan preparation read-only and keeps application disabled unless the server starts with `--allow-mutation`.
- Preserves native semantic objects, backup, atomic replacement, native reload validation, automatic failure recovery and explicit rollback.
- Never applies advisory diagnostics or correction plans automatically.
- Preserves project-root confinement, hardened XML parsing, bounded results and secret redaction.
- Runs directly in the Apache Hop JVM on Java 21 with MCP Java SDK 2.0.1 over STDIO.
- Built against Apache Hop 2.19.0 and compatibility-tested against the current Apache Hop 2.20.0-SNAPSHOT line.
- GitHub Actions runs `mvn -B clean verify` and validates the Marketplace ZIP before creating `v0.9.0`.

This is a community project, not an official Apache Software Foundation release.
