# MCP Connector for Apache Hop 2.2.1

Released on 2026-09-24. This maintenance release focuses on scalability and repeated-query efficiency for production ETL projects while preserving the public MCP tool contract, Java 21 baseline, Apache Hop 2.19.0 compile baseline, Hop 2.20.0-SNAPSHOT compatibility profile, MCP Java SDK 2.0.1, protocol revision 2025-11-25, and production STDIO transport.

## Performance and scalability

- Added a bounded incremental `HopProjectDefinitionIndex` shared by metadata dependency and impact analysis. Unchanged `.hpl`/`.hwf` definitions are reused across MCP calls, while modified definitions are reparsed individually.
- Cached normalized definition/component search text and reused parsed Hop XML documents to avoid repeated XML parsing in hot inspection paths.
- Impact analysis now builds output edges only from the affected subgraph, removes the unused outbound graph, and avoids repeated filesystem existence/real-path checks for already indexed project references.
- Execution history now applies path/date filters before loading execution state and stops once the requested page plus the has-more sentinel has been established.
- Evidence-based diagnosis reuses one native Execution Information Location session for execution detail, component metrics, and previous-execution history instead of repeatedly initializing and closing the backend.
- Stored execution-data profiling now enforces global work budgets of 500,000 field evaluations and 20,000 tracked distinct values per request, in addition to existing row, field, sample, and response limits.
- Connection tests and schema comparison reuse a shared bounded daemon deep-check worker rather than creating one executor per request.

## Benchmark evidence

A same-runner synthetic benchmark compared `v2.2.0` with this release on GitHub Actions using Java 21.0.12.1, 4 available CPU cores, and approximately 4 GiB maximum heap. The workload generated projects with 1,000, 2,500, and 5,000 definitions and a 64-node affected dependency chain.

| Definitions | Scenario | v2.2.0 | 2.2.1 | Relative improvement |
|---:|---|---:|---:|---:|
| 1,000 | repeated warm impact analysis | 352 ms | 26 ms | ~13.5x |
| 1,000 | one unrelated definition changed | 242 ms | 20 ms | ~12.1x |
| 2,500 | repeated warm impact analysis | 533 ms | 49 ms | ~10.9x |
| 2,500 | one unrelated definition changed | 471 ms | 45 ms | ~10.5x |
| 5,000 | repeated warm impact analysis | 944 ms | 92 ms | ~10.3x |
| 5,000 | one unrelated definition changed | 942 ms | 98 ms | ~9.6x |

The cold 5,000-definition run changed from 959 ms in `v2.2.0` to 1,346 ms in `2.2.1`: the new release intentionally spends more work on the initial reusable index so subsequent calls and incremental changes are substantially cheaper. These measurements are synthetic regression evidence from workflow run `36013572630`, not a universal production-latency guarantee.

## Validation

- The verified mainline build runs 87 tests with zero failures/errors/skips.
- Clean Apache Hop 2.19 installation smoke remains required.
- Apache Hop 2.20.0-SNAPSHOT compatibility remains required.
- MCP Conformance 2025-11-25 remains a required regression gate using the official runner and explicit expected-failures baseline.
- Production remains STDIO; the localhost HTTP adapter remains test-only.
- Existing deny-by-default capability gates, project-root confinement, secure XML parsing, secret redaction, response budgets, transactional semantic mutation, SHA-256 preconditions, protected backups, and rollback remain unchanged.

## Release artifacts

The release publishes the Marketplace ZIP, CycloneDX JSON/XML SBOMs, SHA-256 checksums, provenance attestation, and SBOM attestation. The package validation continues to require Jandex metadata, legal files, embedded version alignment, and exclusion of Apache Hop runtime jars.

The Maven POM, embedded version resource, release notes, Marketplace catalog entries, artifact filename, and `v2.2.1` tag are aligned at `2.2.1`.
