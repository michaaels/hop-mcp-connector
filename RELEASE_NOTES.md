# MCP Connector for Apache Hop 2.1.0

Prepared on 2026-09-22; not published. This release candidate keeps the Java 21, Apache Hop 2.19.0 compile baseline, Hop 2.20.0-SNAPSHOT compatibility profile, MCP Java SDK 2.0.1, and MCP protocol revision 2025-11-25 over STDIO.

- Bound project traversal, file reads, content scans, catalog hashing, response sizes, web response bodies, log output, and mutation backup storage. Hidden connector-control files are excluded from project tools.
- Added stable pagination and explicit completeness/truncation indicators to project listings and searches. File reads and search results redact secrets before returning data.
- Added typed, sanitized MCP error results and strict bounded output schemas for 30 tools.
- Isolated semantic-mutation backups beneath `.hop-mcp/backups/`, bounded their count and size, added expiry cleanup, and report whether replacement used an atomic filesystem move.
- Kept deep checks, execution, authoring/mutation, and Hop Web tools hidden unless their matching server-side opt-in is enabled.
- Pinned GitHub Actions to full commit SHAs. CI validates the Hop 2.19.0 archive checksum and clean-install STDIO smoke, runs the Hop 2.20.0-SNAPSHOT profile, inspects ZIP/Jandex metadata, and generates a CycloneDX SBOM. Tag releases verify tag/POM/artifact consistency and attach checksums and GitHub attestations.

The repository's STDIO integration tests exercise the protocol handshake and tool calls, validate advertised schemas against actual results, and cover enabled execution, mutation and rollback, correction plans, and a local web-response fixture. The official MCP Conformance Suite was not run: its current runner targets Streamable HTTP and requires capabilities this tools-only STDIO server does not advertise. Installation through the published Hop Marketplace catalog still requires post-publication verification.

Local verification on 2026-09-22: `mvn -Djavax.net.ssl.trustStoreType=Windows-ROOT -B clean verify` and `mvn -Djavax.net.ssl.trustStoreType=Windows-ROOT -B -P hop-2.20 clean verify` both passed with 63 tests, zero failures, and two skipped environment-dependent tests. Both produced the package and CycloneDX SBOM; the ZIP layout, Jandex index, embedded version, required legal files, and Hop-runtime exclusions were checked. The separate clean Hop 2.19.0 installation smoke remains pending: the local Windows client could not verify certificate revocation when contacting the Apache download server, so that smoke must pass in the configured Ubuntu CI job.

The Maven POM and both Marketplace catalog entries are aligned at `2.1.0`. No tag, release, or publication was created as part of this preparation.
