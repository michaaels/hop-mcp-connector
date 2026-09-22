# AGENTS.md

This is the operational contract for agents working in this repository.

## Code discovery

This project uses codebase-memory-mcp. Prefer `search_graph`, `trace_path`, `get_code_snippet`, `query_graph`, and `search_code` for code discovery. Run `index_repository` first if the checkout is not indexed. Fall back to `rg` for string literals, non-code files, or when graph results are insufficient.

Always prefix shell commands with `rtk` (use `rtk proxy` for commands without an RTK wrapper).

## Mission and baseline

Maintain a small, secure, native-Java MCP plugin for Apache Hop. The Marketplace edition must remain installable as one Hop plugin and expose `hop mcp` through Hop's `@HopCommand` discovery.

- Java 21.
- Apache Hop 2.19.0 Maven compile baseline; 2.20.0-SNAPSHOT compatibility profile.
- MCP Java SDK 2.0.1; protocol baseline 2025-11-25.
- Production transport is STDIO.
- Maven coordinate: `io.github.michaaels:hop-mcp-connector`.
- Product name: `MCP Connector for Apache Hop`.

Keep `hop mcp` as the CLI command. Do not rename Java packages solely for branding. Do not reintroduce Python or a Java subprocess bridge into the Marketplace runtime.

## Architecture and security rules

1. Preserve native Hop integration: `@HopCommand`, `IHopCommand`, `PluginRegistry`, `PipelineMeta`, `WorkflowMeta`, metadata providers, run configurations, and metadata injection APIs.
2. Never use arbitrary XML replacement for pipeline/workflow edits. Keep mutations semantic and transactional.
3. Never add execution, mutation, or broader authorization in a patch release.
4. Project paths must remain under the configured root after normalization and symlink resolution.
5. Keep DTDs, external entities, external schemas, and XInclude disabled.
6. Redact secrets before returning or logging data. Never expose passwords, tokens, the full environment, or arbitrary system properties.
7. STDIO stdout is protocol-only. Application and Hop logs belong on stderr.
8. Deep validation that can resolve fields or contact external systems stays explicit opt-in.
9. Deep-check, execution, mutation/authoring, and Hop Web tools must be absent from `tools/list` unless their matching flag is enabled. Keep service-layer authorization as defense in depth.
10. Do not bundle `hop-core`, `hop-engine`, `hop-ui`, or other Hop runtime jars in the Marketplace ZIP; keep Hop dependencies `provided` where applicable.
11. Do not weaken file-size, scan-count, result-count, input-size, operation-count, or traversal-depth bounds without a documented reason and tests.
12. Preserve and verify Jandex plugin metadata generation.

## Bounded operation contract

- Keep project file reads at or below 4 MiB per file; content scans and catalog hashing are bounded to 32 MiB.
- Project traversal is capped at 50,000 visited entries, 5,000 files, and depth 64. Keep pagination stable and preserve `count_complete`, `has_more`, and truncation signals when a scan ends early.
- A structured page contains at most 200 results, and the combined MCP tool response is capped at 512 KiB. Do not solve response pressure by removing bounds; return a safe error asking for a smaller page or narrower query.
- `hop_read_text` defaults to 64 KiB and allows at most 128 KiB per chunk. Redact the complete bounded file before slicing; offsets count UTF-8 bytes in that redacted view. Search must redact before matching snippets.
- Hop Web reads at most 4 MiB and returns at most 64 KiB of redacted response body. Preserve GET/HEAD-only access, configured-base-path confinement, disabled redirects, credential handling, and response bounds.
- Mutation backup storage is under `.hop-mcp/backups/`, is not exposed through project tools, and is capped at 32 MiB and 100 transactions. Rollback expires after one hour; expired backup files are pruned during a later mutation or expiry check. Return only a protected-backup marker, never the local path.
- `atomic_replace_used` reports whether the filesystem supported an atomic move. Preserve the fallback, native Hop reload validation, recovery attempt, and hash-checked rollback; do not describe every filesystem write as atomic.

## New-tool completion checklist

A new MCP tool is complete only when it has a stable name, concise description, strict bounded input schema, output schema where applicable, accurate annotations, a capability group, server-side authorization, bounds and redaction, unit and STDIO integration tests, documented error behavior, performance consideration, and user documentation.

## Before editing and validation

Read the relevant source and Apache Hop 2.19 API before guessing a method signature. Prefer Apache Hop release/2.19.0 sources when compatibility matters.

For ordinary changes run:

```bash
rtk mvn -B clean verify
```

For compatibility changes run:

```bash
rtk mvn -B -P hop-2.20 clean verify
```

For packaging changes inspect `target/hop-mcp-connector-${version}.zip`. It must contain `plugins/misc/hop-mcp-connector/`, `LICENSE`, and `NOTICE`, include the generated Jandex index inside the plugin jar, and must not contain Hop runtime jars. The build must also produce CycloneDX SBOM files in `target/`. CI is configured for a checksum-verified clean Hop 2.19.0 install smoke and the separate Hop 2.20.0-SNAPSHOT compatibility build; describe configured coverage without asserting a run passed unless you inspected its result.

Update tests when adding or changing a tool. Preserve coverage for protocol handshake, schema failures, unknown tools, error results, security bounds, package layout, and clean shutdown.

## Branding, versioning, and releases

- The project is independent of the Apache Software Foundation. Keep the complete Apache License 2.0 text and attribution/mark notice in source and ZIP.
- Keep Maven, Marketplace catalog, `version.xml`, server metadata, release notes, ZIP filename, and release tag consistent.
- Artifact and ZIP name: `hop-mcp-connector-${version}`; GitHub tag: `v${version}`.
- Keep Marketplace minimum Hop version aligned with the verified stable Hop 2.19.0 baseline; describe Hop 2.20.0-SNAPSHOT only as a compatibility profile.
- Release workflows are tag-controlled and must validate tag/POM consistency; never publish solely because a commit message contains `[release]`.
- Release gates must validate the verified package and Jandex index, retain the CycloneDX SBOM, create SHA-256 checksums, and produce archive-provenance and SBOM attestations for the release artifact. A workflow definition is not evidence of a successful run.
- Do not push, tag, release, or publish artifacts unless the user explicitly asks.

## Semantic mutation rules

Native writes must use Hop semantic objects, not arbitrary XML string replacement. Existing-definition apply requires an expected SHA-256; every write must preserve preview, protected backup where an existing file is replaced, temporary-file replacement with an atomic-move status, native reload validation, recovery attempt, transaction ID, and hash-checked rollback.
