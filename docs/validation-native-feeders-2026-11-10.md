# Apache Hop native Feeders authoring verification

This historical implementation was subsequently simplified; see [the current verification](validation-simplified-feeders-2026-11-10.md).

The user required the loader to run entirely in Apache Hop. The final runtime uses native pipeline/workflow definitions, native MariaDB metadata and native SQL actions. Custom Java/PHP loader code was retired from the active ETL tree.

Three connector gaps were reproduced and fixed:

- Missing native named-parameter creation: `add_parameter` now calls PipelineMeta/WorkflowMeta parameter APIs and retains preview, SHA-256 preconditions, reload and rollback.
- Checksum's algorithm enum was hidden by the structural `TYPE` exclusion. An exception is limited to enum metadata whose serialized key identifies safe configuration; structural type fields remain protected.
- Text File Input's native schema has 62 scalar properties, but the advertised output schema allowed only 50 and SDK output validation rejected the call. Discovery now allows 200 and rejects overflow before returning. Mutation inputs remain limited to 50 properties and responses to 512 KiB. This is a discovery contract fix, not expanded write authorization.

Regression coverage: duplicate named parameters, preview/apply/native reload/rollback, safe enum injection versus structural type rejection, acceptance of 62-property discovery output and rejection above its bound. Final builds with Java 21 passed: Hop 2.19 baseline, 173 tests / 0 failures / 0 errors / 10 skipped; Hop 2.20.0 compatibility profile, 173 / 0 / 0 / 3 skipped. Packaging and SBOM generation passed. Read-only STDIO catalog: 24 tools, 92,525 bytes. No release was published.

Runtime verification via temporary authorized STDIO MCP sessions loaded 47,907 CSV rows into the test database, checked all eleven raw fields and source hashes, and checked coordinates against the supplied compiled inventory view. All matched. Native Hop transaction rollback preserved an identical full-cut fingerprint after a controlled failure following publication. Missing-file, empty-file and invalid-numeric cases failed as expected. Idempotence was checked with a six-row fixture, then a standard HopRun execution against the installed project passed. The fixture was removed by a native Hop SQL action.

Native Text File Input was selected after CSV Input failed on the UNC URI with spaces. The complete load took 471.547 seconds in the measured run. Shared transactional connections disable native batch inserts; a temporary indexed inventory bounds the database join work. Fixed eleven-column input metadata is an upstream format contract.

Detailed local report: `D:/dev/feeders/etl/VALIDACION.md`. Evidence: `D:/dev/build_hop_web/mcp/feeders-native/20261109-162708`. Production business-schema writes and main workflows that send messages were not executed. Global MCP permission flags were not changed.
