# Changelog

## 0.9.0 - 2026-09-20

- Added immutable, session-scoped semantic correction plans with native previews.
- Bound each plan to its own SHA-256 and the definition SHA-256 observed during preparation.
- Added explicit single-use plan application through the existing transactional semantic mutator.
- Added bounded per-plan audit events, one-hour expiration and a 100-plan session limit.
- Rejected altered, expired, reused and stale-definition plans without weakening mutation authorization.

## 0.8.0 - 2026-09-15

- Added `hop_test_definition`, a gated structural validation, native deep-check and local execution cycle.
- Added bounded machine-readable diagnostics across validation, checking, execution results and redacted logs.
- Added advisory correction candidates that identify relevant semantic tools without applying changes automatically.
- Prevented later phases from running when structural validation or a requested deep check fails.
- Kept deep checking and execution behind their existing independent server opt-ins.

## 0.7.0 - 2026-09-14

- Added transactional `update_component` support for existing pipeline transforms and workflow actions.
- Added scalar property updates and replacement semantics for requested one-level tabular groups.
- Preserved component identity, canvas position, hops and unspecified configuration during updates.
- Added preview, apply, native reload and rollback coverage for scalar and tabular updates.

## 0.6.0 - 2026-09-14

- Added schema discovery for safe one-level tabular property groups exposed by Apache Hop metadata injection.
- Added bounded `property_groups` support to transactional `add_component` operations.
- Added native Injector-field authoring and reload coverage, including security and invalid-schema rejection tests.
- Kept nested collections, secret-looking fields and unsupported properties outside the semantic mutation surface.

## 0.5.0 - 2026-09-14

- Added filtered, paginated discovery of native pipeline transforms and workflow actions.
- Added per-plugin schemas generated from Apache Hop's metadata-injection model.
- Added transactional `add_component` authoring for pipelines and workflows, composable with hop creation in one mutation.
- Limited component configuration to bounded scalar properties and excluded secret-looking and collection properties.
- Added native reload tests for authored pipelines, workflow actions, injected values, hops, and unsafe-property rejection.

## 0.4.0 - 2026-09-10

- Added opt-in synchronous and asynchronous local pipeline/workflow execution.
- Added bounded execution concurrency, timeouts, status, cancellation, and redacted Hop logs.
- Added previewable native semantic mutation for definition metadata, component names, and hop enabled state.
- Added native component move/removal and hop add/removal operations.
- Added `hop_capabilities` and session-aware live synchronization adapters for Hop Desktop and Hop Web/RAP.
- Added a bounded project event bridge with expiring heartbeats, acknowledgements, atomic event writes, and dirty-tab protection.
- Excluded the internal `.hop-mcp` control directory from project catalog, search, read, and mutation tools.
- Verified the same source against Hop 2.19.0 and the current 2.20.0-SNAPSHOT line.
- Added SHA-256 preconditions, backups, atomic replacement, native reload validation, automatic recovery, and explicit session rollback.
- Kept Hop Web read-only and separated execution and mutation authorization flags.
- Added tests for mutation preview, apply, rollback, traversal protection, operation bounds, and opt-in enforcement.

## 0.3.1 - 2026-09-08

- Added bounded, paginated project cataloging with file metadata and SHA-256 fingerprints.
- Added consolidated definition context and project-relative dependency resolution.
- Added filtered, paginated Apache Hop plugin discovery.
- Added opt-in, bounded Hop Web GET/HEAD access with base-path confinement and secret redaction.
- Made MCP JSON mapper and schema validator selection explicit for Hop's isolated plugin classloader.
- Routed Hop console logging to stderr while STDIO is active so stdout remains JSON-RPC-only.
- Extended error, XML, response-body and response-header secret redaction.

## 0.3.0 - 2026-08-31

- Rebuilt Marketplace runtime in native Java 21.
- Added Apache Hop `@HopCommand` integration as `hop mcp`.
- Added Hop GUI Tools menu integration.
- Replaced Python/Java bridge runtime with direct MCP Java SDK 2.0.1 integration.
- Added 12 read-only MCP tools for inspection, search, lineage, validation and dependencies.
- Added project-root confinement, bounded scanning, secure XML parser and secret redaction.
- Added opt-in Apache Hop native deep checker.
- Added Marketplace GitHub Releases catalog/repository metadata.
- Added Codex/Cloud `AGENTS.md` and AI development guide.
