# MCP Connector for Apache Hop 2.2.2

Released on 2026-09-25. This maintenance release improves bounded project indexing, metadata-reference accuracy, deep-check concurrency and operational visibility. It keeps Java 21, the Apache Hop 2.19.0 compile baseline, the 2.20.0-SNAPSHOT compatibility profile, MCP Java SDK 2.0.1, protocol revision 2025-11-25 and STDIO transport.

## Changes

- Project scans examine up to 50,000 regular files while retaining at most 5,000 matching definitions. Pagination is stable and traversal, depth, read and response bounds remain enforced.
- The shared definition index uses immutable snapshots, single-flight refresh, generation-aware mutation invalidation and filesystem size, modification time and optional file key for incremental reuse. The stamp is a change-detection hint, not a cryptographic integrity check.
- Metadata dependency and impact analysis share bounded, typed references extracted from native Hop metadata dependencies, annotated properties and an exact-leaf XML fallback.
- Connection and schema deep checks restore the JVM-global JDBC login timeout after each check. The bounded worker exposes queue, cancellation, timeout and degraded-health counters. Other JVM code can still change this global timeout concurrently.
- The read-only `hop_runtime_metrics` MCP tool exposes aggregate index and deep-check worker metrics without project content or secrets.
- A manual and weekly benchmark workflow compares `v2.2.1` with the selected commit using shared synthetic fixtures and publishes JSON, CSV and a Job Summary. Latency is informational; fixture and index correctness are checked.

## Validation and distribution

The release gate builds and verifies against Apache Hop 2.19.0, checks the MCP 2025-11-25 conformance baseline, runs a clean Hop 2.19.0 installation smoke, and builds the Hop 2.20.0-SNAPSHOT compatibility profile. The tag workflow validates version alignment and the Marketplace ZIP, then publishes the ZIP, CycloneDX JSON/XML SBOMs, SHA-256 checksums, archive provenance and SBOM attestations.

The Maven version, embedded version resource, Marketplace catalog, ZIP name and `v2.2.2` release tag are aligned at `2.2.2`.
