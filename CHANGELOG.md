# Changelog

## 2.0.0 - 2026-09-22

- Renamed the Maven, Marketplace, plugin installation, ZIP, and MCP server identifiers to `hop-mcp-connector`.
- Changed the Marketplace product name to **MCP Connector for Apache Hop** and clarified independent-project and trademark attribution.
- Replaced the abbreviated license notice with the complete Apache License 2.0 and added a distributable `NOTICE`.
- Switched the STDIO contract test to MCP revision 2025-11-25 and expanded it for tool discovery, hidden authorization-gated tools, invalid input, unknown tools, handler errors, and EOF.
- Omitted deep-check, execution, mutation/authoring, and Hop Web tools from `tools/list` unless their matching opt-in is enabled, while preserving service-layer checks.
- Added MCP tool annotations from the MCP Java SDK 2.0.1.
- Added strict bounded success and error output schemas for eleven core, inspection, validation, execution, and mutation tools; normalized MCP tool errors to stable codes and categories.
- Added bounds and truncation indicators for project inspection and validation diagnostics.
- Replaced commit-message-based releases with explicit version-tag validation.
- Expanded STDIO end-to-end coverage to call native component-schema discovery, synchronous execution, and asynchronous execution status with execution explicitly enabled. Actual `structuredContent` for all eleven output-schema tools is validated against its advertised JSON Schema using the MCP SDK validator.
- Documented that the official MCP Conformance Suite was not run for this release and that installation through the published Hop Marketplace catalog requires post-publication verification.

## 1.0.0 - 2026-09-21

- Promoted the native semantic MCP tool contract to its first stable release.
- Added an in-process STDIO integration test covering initialization, the required initialized notification, configuration discovery, and consecutive validation calls.
- Derived the protocol, configuration, and startup-log version from one Maven-filtered resource instead of duplicated Java literals.
- Hardened correction-plan SHA-256 validation and diagnostics without exposing expected or received digests.
- Added preview coverage for creating a new native Hop definition without writing it.
- Added external-runtime smoke coverage for the MCP handshake and consecutive definition validations.
- Added an explicit, auditable 1.0 release gate for Hop 2.19, the Hop 2.20 compatibility profile, Marketplace packaging, and security invariants.

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
