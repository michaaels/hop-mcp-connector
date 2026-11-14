# Simplified native Feeders authoring

The user requested minimal, understandable native Hop development and removal of CSV_ROW, SOURCE_FILE, SOURCE_SHA256 and BATCH_ID. Feeders now uses 14 components instead of 34: five entry transforms, five workflow actions and four reader transforms. One Text File Input reads the three identically structured files, followed by Value Mapper, Get Variables and Table Output. Native SQL validates all technologies before replacing the requested cut. No JavaScript is used by this loader.

The test destination has 33 columns instead of 37. A full isolated load produced 47,907 correct rows; all raw CSV fields and coordinates matched independent checks. Missing/empty/invalid input cases failed correctly. A failure injected only into the test SQL after publication preserved the prior fingerprint after rollback. A nine-row fixture with genuine duplicates stayed at nine after repeating the load. The old table is retained as a backup.

All 24 pre-existing JavaScript snippets were formatted through semantic MCP writes. Executable ASTs were preserved and native-reloaded text matched the checked output. Maximum line length changed from 344 to 151. Unrelated production workflows that send messages were not run.

Connector changes:

- Authoring instructions prefer native components, shared readers, minimal business fields and small clear flows.
- Script style warnings are bounded, source-free and advisory; they never rewrite or execute code.
- Native parameter removal preserves other definitions and the existing preview/hash/reload/rollback contract. Unused optional input was removed from parameter creation capabilities.

Java 21 builds passed on Hop 2.19 and Hop 2.20.0: 175 tests, zero failures/errors, 10 and 3 skipped respectively. Global authorization flags were not changed. No release was published.

Measured full-load time: 256.764 seconds. The prior run took 471.547 seconds; these are separate executions under varying server/network conditions, not a controlled speedup benchmark.

Detailed local report: `D:/dev/feeders/etl/VALIDACION.md`. Evidence: `D:/dev/build_hop_web/mcp/feeders-simple/20261110`.
