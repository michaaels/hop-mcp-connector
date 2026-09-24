# MCP Connector for Apache Hop 2.2.0

Released on 2026-09-24. This release expands the connector from a hardened Apache Hop MCP integration into a broader production-ETL operations surface while retaining Java 21, the Apache Hop 2.19.0 compile baseline, the Hop 2.20.0-SNAPSHOT compatibility profile, MCP Java SDK 2.0.1, and protocol revision 2025-11-25 over STDIO.

## Production ETL capabilities

- Added native metadata discovery and inspection: `hop_metadata_types`, `hop_metadata_list`, `hop_metadata_get`, and bounded metadata dependency analysis.
- Added explicit deep-check tools for RDBMS connection testing and table schema comparison with bounded timeouts and structured schema-drift results.
- Added native run-configuration resolution, semantic definition diff, environment comparison, and bounded project impact analysis.
- Added native Execution Information Location reads for history, detail, child executions, component metrics, stored execution-data profiling, and evidence-based execution diagnosis.
- Kept Apache Hop as the source of truth: the new operations reuse Hop metadata providers, serializers, run configurations, execution information, `PipelineMeta`, `WorkflowMeta`, and database APIs instead of introducing a parallel ETL runtime.

## Security and correctness

- Stored execution-data profiling now requires `--allow-execution` because it can return operational row samples.
- Profiling counts physical rows once regardless of the number of requested fields and reports availability, completeness, truncation, distinct bounds, and redacted samples accurately.
- Impact analysis now follows dependent definitions, resolves project/Hop path variables, avoids prefix matches such as `DIM_SITE` matching `DIM_SITE_ARCHIVE`, and returns only dependency edges inside the affected subgraph.
- Existing deny-by-default capability gates, project-root confinement, secure XML parsing, redaction, response budgets, deep-check authorization, transactional semantic mutation, SHA-256 preconditions, protected backups, and rollback remain in force.

## MCP conformance and CI

- MCP Conformance 2025-11-25 remains a required CI gate.
- The official `@modelcontextprotocol/conformance@0.2.0-alpha.11` runner executes the frozen `2025-11-25` requirement set against a localhost-only test adapter that reuses the production server definition, registry, schemas, and handlers.
- Known scenarios belonging to unsupported MCP capabilities such as prompts, resources, completion, sampling, elicitation, and conformance fixture tools are tracked through the runner's official `--expected-failures` mechanism. New unexpected failures and stale baseline entries fail CI.
- Production transport remains STDIO.
- The verified release build runs 84 tests and requires the main build, clean Apache Hop 2.19 smoke, Apache Hop 2.20 compatibility, and MCP Conformance jobs to succeed.

## Build and dependency maintenance

- Updated JUnit Jupiter to 6.1.3 and refreshed Tomcat and Maven build tooling while keeping `fmt-maven-plugin` at the repository's established 2.25 formatting baseline.
- Release packaging continues to validate the Marketplace ZIP layout, Jandex index, embedded version, legal files, and exclusion of Apache Hop runtime jars.
- The release publishes the Marketplace ZIP, CycloneDX JSON/XML SBOMs, SHA-256 checksums, provenance attestation, and SBOM attestation.

The Maven POM, embedded version resources, release notes, Marketplace catalog entries, artifact filename, and `v2.2.0` tag are aligned at `2.2.0`.
