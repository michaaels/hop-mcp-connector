# MCP Connector for Apache Hop

MCP Connector for Apache Hop is an independent community project and is not an Apache Software Foundation project or an official Apache Hop component. It is a native Java Apache Hop plugin that exposes bounded project inspection and selected native Hop APIs over MCP STDIO.

## Architecture

```text
MCP client
  -> STDIO
  -> hop mcp
  -> HopMcpCommand
  -> HopMcpServer (MCP Java SDK)
  -> HopMcpService
  -> bounded project inspection / native Hop APIs
  -> Apache Hop runtime
```

The plugin uses Hop's `@HopCommand`, plugin registry, `PipelineMeta`, `WorkflowMeta`, metadata injection, and native serializers. It does not use a Python or Java subprocess bridge. Pipeline and workflow changes use native semantic objects rather than arbitrary XML replacement.

## Features

- Read-only project catalog, search, definition inspection, lineage, dependency analysis, and structural validation.
- Optional native Hop deep checks, local execution, semantic authoring/mutation, and Hop Web GET/HEAD access. Each requires a separate explicit server flag.
- Incremental project-definition indexing, precise typed metadata references, and a read-only `hop_runtime_metrics` tool for bounded index and deep-check worker counters.
- Transactional semantic mutation with preview, SHA-256 preconditions, protected backups, temporary-file replacement, native reload validation, automatic recovery, and explicit rollback. The result reports whether the filesystem supported an atomic move.
- Bounded inputs, scans, results, execution and logs; project-root confinement; secure XML parsing; sensitive-data redaction.
- Optional Hop Desktop and Hop Web live synchronization, explicitly started from the UI and without a network listener.

## Security defaults

Inspection and structural validation are available by default. Deep checks, execution, authoring/mutation, and Hop Web tools are omitted from `tools/list` unless their respective flags are enabled. The service layer also checks authorization before deep checks, execution, writes, and web requests.

In `hop_config`, `read_only` is true only when all four opt-in groups are disabled. Enabling deep checks, execution, or read-only Hop Web access also makes it false; use `definition_write_enabled` and the individual `allow_*` fields to identify which capability is authorized.

```text
hop mcp --root <project>
hop mcp --root <project> --allow-deep-check
hop mcp --root <project> --allow-execution
hop mcp --root <project> --allow-mutation
hop mcp --root <project> --allow-web-api --web-url http://127.0.0.1:8080/hop
```

Only enable the flags trusted MCP clients need. Execution can reach any systems available to the Hop process. Hop Web credentials are configured server-side; callers cannot supply authorization headers.

When using a custom `HOP_CONFIG_FOLDER`, initialize it before starting `hop mcp`. Apache Hop 2.19 prints a configuration-creation message to stdout if it creates `hop-config.json` during CLI startup, before the MCP command can redirect Hop logs to stderr. The clean-install smoke uses the configuration shipped with the Hop distribution.

## Requirements and compatibility

- Java 21.
- The Maven compile baseline is Apache Hop 2.19.0. The `hop-2.20` profile targets the 2.20.0-SNAPSHOT line.
- MCP Java SDK 2.1.0; protocol baseline 2025-11-25 over STDIO.

| Connector | Apache Hop | Java | MCP Java SDK | MCP revision |
|---|---|---:|---:|---|
| 2.0.0 | 2.19.0 compile baseline; 2.20.0-SNAPSHOT profile | 21 | 2.0.1 | 2025-11-25 |
| 2.1.0 | 2.19.0 compile baseline; 2.20.0-SNAPSHOT profile | 21 | 2.0.1 | 2025-11-25 |
| 2.2.0 | 2.19.0 compile baseline; 2.20.0-SNAPSHOT profile | 21 | 2.0.1 | 2025-11-25 |
| 2.2.1 | 2.19.0 compile baseline; 2.20.0-SNAPSHOT profile | 21 | 2.0.1 | 2025-11-25 |
| 2.2.2 | 2.19.0 compile baseline; 2.20.0-SNAPSHOT profile | 21 | 2.0.1 | 2025-11-25 |
| 2.2.3 | 2.19.0 compile baseline; 2.20.0-SNAPSHOT profile | 21 | 2.0.1 | 2025-11-25 |
| 2.2.4 (tag only; no release) | 2.19.0 target; Windows verification failed | 21 | 2.0.1 | 2025-11-25 |
| 2.2.5 | 2.19.0 compile baseline; 2.20.0-SNAPSHOT profile | 21 | 2.0.1 | 2025-11-25 |
| 2.2.6 | 2.19.0 compile baseline; 2.20.0-SNAPSHOT profile | 21 | 2.0.1 | 2025-11-25 |
| Unreleased | 2.19.0 compile baseline; 2.20.0-SNAPSHOT profile | 21 | 2.1.0 | 2025-11-25 |

The MCP Java SDK 2.1.0 supports protocol revision `2025-11-25`. Revision `2026-07-28` is not supported by this SDK line and is not implemented here. Apache Hop 2.19.0 is the stable compile baseline; the `hop-2.20` profile is a compatibility check against the 2.20.0-SNAPSHOT line, not a stable-support promise. The CI workflow is configured to verify the 2.19.0 archive checksum, install the Marketplace ZIP into a clean distribution, and exercise `hop mcp` over STDIO, as well as build against the 2.20.0-SNAPSHOT line. Installation through the published Hop Marketplace catalog requires post-release verification.

## Recent release history

### 2.2.6 — STDIO shutdown fix

- Keeps stdout reserved for MCP until the process exits. Native pipeline logs emitted after a timeout, cancellation, EOF or startup failure stay on stderr and cannot corrupt the JSON protocol during shutdown.
- Adds deterministic tests for worker logs emitted after command exit, covering both normal EOF and startup failure.
- Preserves existing tools, permission flags, execution limits and pipeline behavior. Includes the maintenance changes in 2.2.5.

### 2.2.5 — Verified maintenance release

- Resolves project paths before reads and semantic writes, excludes Windows junctions from scans and protected backups, and prevents aliases from exposing internal or external files.
- Rejects new connection/schema checks while the JDBC worker remains degraded; returns retryable `DEEP_CHECK_WORKER_UNHEALTHY` until the cancelled callable actually exits.
- Verifies native acceptance against the installed connector JAR, strengthens conformance checks while accepting repeated informational events, bounds STDIO test cleanup, and preserves Hop Web encoded query parameters.
- Adds published-ancestor benchmark selection and a manual full-Hop workflow, protects existing benchmark evidence, and canonicalizes Windows test fixtures to resolve the failed 2.2.4 validation.

### 2.2.4 — Tag only; no published release

The [v2.2.4 verification run](https://github.com/michaaels/hop-mcp-connector/actions/runs/36826484672) failed on Windows because several tests compared abbreviated `RUNNER~1` temporary paths with their canonical `runneradmin` paths. The tag contained the confinement, degraded-worker and verification improvements described above, but the release gate blocked publication: **there is no 2.2.4 GitHub Release or downloadable release ZIP**. Its tag is retained for traceability; the corrected changes were published in 2.2.5.

### 2.2.3 — Bounded indexing and native acceptance

- Reduced initial native metadata extraction and repeated filesystem ordering work while preserving typed references, immutable snapshots, single-flight refresh and change detection.
- Enforced index read budgets for malformed definitions and failed reads, and marked incomplete filesystem scans accurately.
- Extended Hop Web deadlines to response-body reception and preserved the native `-1` unavailable-error-count sentinel in execution timeout responses.
- Added native-reference equivalence and operational acceptance on complete Hop installations, mandatory Linux symlink checks, raw conformance-result verification and reusable full-Hop benchmark tooling. Fixed idle SSE headers in the test-only adapter; production transport remains STDIO.

See [CHANGELOG.md](CHANGELOG.md) for the full history, [RELEASE_NOTES.md](RELEASE_NOTES.md) for the current release, and [GitHub Releases](https://github.com/michaaels/hop-mcp-connector/releases) for downloadable packages.

## Installation

Build the Marketplace ZIP and CycloneDX SBOM with `mvn -B clean verify`. This release checkout builds `target/hop-mcp-connector-2.2.6.zip`. SBOM files are `target/bom.json` and `target/bom.xml`. Install the ZIP into Hop's `plugins/misc/hop-mcp-connector/` directory or use the repository/catalog metadata in `marketplace/`. Restart Hop, then run `hop mcp --help`.

Hop runtime libraries (`hop-core`, `hop-engine`, and `hop-ui`) are provided by Hop and are not included in the ZIP. The ZIP includes the project license and notice.

## MCP client configuration

Example Codex configuration for Windows:

```toml
[mcp_servers.apache_hop]
command = "C:\\hop\\hop.bat"
args = ["mcp", "--root", "C:\\Hop\\project"]
```

Add only the authorization flags required for the intended workflow.

## Tools and permission groups

| Group | Tools | Default |
|---|---|---|
| Core | `hop_config`, `hop_capabilities`, `hop_live_ui_status` | enabled |
| Inspect | `hop_plugins`, `hop_metadata_types`, `hop_metadata_list`, `hop_metadata_get`, `hop_metadata_dependencies`, `hop_resolve_configuration`, `hop_definition_diff`, `hop_impact_analysis`, `hop_environment_diff`, `hop_runtime_metrics`, `hop_catalog`, `hop_list_definitions`, `hop_inspect`, `hop_context`, `hop_component`, `hop_component_lineage`, `hop_read_text`, `hop_search`, `hop_find_table`, `hop_dependencies` | enabled |
| Validate | `hop_validate` | enabled |
| Deep check | `hop_deep_check`, `hop_test_connection`, `hop_schema_compare` | `--allow-deep-check` |
| Author | `hop_component_types`, `hop_component_schema`, `hop_prepare_correction_plan`, `hop_apply_correction_plan`, `hop_correction_plan_status`, `hop_mutate_definition`, `hop_rollback_mutation` | `--allow-mutation` |
| Execute | `hop_test_definition`, `hop_execute`, `hop_start_execution`, `hop_execution_status`, `hop_execution_history`, `hop_execution_detail`, `hop_execution_children`, `hop_execution_metrics`, `hop_diagnose_execution`, `hop_data_profile`, `hop_stop_execution`, `hop_logs` | `--allow-execution`; the optional deep-check phase also requires `--allow-deep-check` |
| Web | `hop_web_request` | `--allow-web-api` |

Tools carry MCP annotations as client hints, not authorization. Strict bounded success and error output schemas are advertised for 47 tools: `hop_config`, `hop_runtime_metrics`, `hop_capabilities`, `hop_live_ui_status`, `hop_context`, `hop_validate`, `hop_inspect`, `hop_catalog`, `hop_list_definitions`, `hop_read_text`, `hop_search`, `hop_find_table`, `hop_dependencies`, `hop_component`, `hop_component_lineage`, `hop_plugins`, `hop_metadata_types`, `hop_metadata_list`, `hop_metadata_get`, `hop_metadata_dependencies`, `hop_test_connection`, `hop_schema_compare`, `hop_definition_diff`, `hop_impact_analysis`, `hop_environment_diff`, `hop_resolve_configuration`, `hop_data_profile`, `hop_component_types`, `hop_component_schema`, `hop_execute`, `hop_start_execution`, `hop_stop_execution`, `hop_execution_status`, `hop_execution_history`, `hop_execution_detail`, `hop_execution_children`, `hop_execution_metrics`, `hop_diagnose_execution`, `hop_deep_check`, `hop_test_definition`, `hop_logs`, `hop_web_request`, `hop_mutate_definition`, `hop_prepare_correction_plan`, `hop_apply_correction_plan`, `hop_correction_plan_status`, and `hop_rollback_mutation`. Other tools still return bounded JSON-compatible results but do not advertise an output schema. Tool failures use an error object with `code`, `category`, `message`, and `retryable`; messages are sanitized. Successful results include both `structuredContent` and JSON `TextContent` for clients that consume text content.

## Bounds, pagination, and errors

Project file reads are limited to 4 MiB per file. Project traversal stops at 50,000 visited entries, 50,000 regular files examined, or depth 64; a scan retains at most 5,000 matching files/definitions. Definition indexing also reads at most 32 MiB per refresh. Content scans and catalog hashing have the same 32 MiB byte budget. Structured pages contain at most 200 rows and the complete tool response is limited to 512 KiB. Results include count-completeness and truncation indicators where applicable, so `count` may describe only the bounded scan. The internal `.hop-mcp/` directory is excluded from project tools.

Rejected XML consumes the definition-index read budget. A reader that fails before returning its bytes conservatively consumes its full allowed budget; `bytes_read` metrics count only returned bytes, not those reservations. Unchanged cached definitions do not consume fresh reads. Filesystem traversal errors also mark scans incomplete rather than reporting an authoritative empty result.

`hop_read_text` returns at most 128 KiB per call (64 KiB by default). Its offsets and byte counts refer to the UTF-8 redacted text view, so callers can request the next chunk without splitting a secret at a chunk boundary. Search redacts file content before matching and returning snippets. Hop Web reads at most 4 MiB and returns at most 64 KiB of response body after redaction.

The Hop Web timeout covers headers and bounded body reception. Timeout or caller interruption cancels the active HTTP exchange; it does not leave a blocking body-reader worker running.

Hop Web access permits only the native `/hop/status`, `/hop/pipelineStatus`, and `/hop/workflowStatus` endpoints, including a configured application/proxy prefix. For a base URL ending in `/hop`, pass a relative path such as `status?json=Y`. GET and HEAD are both checked against the complete target path before HTTP is sent. Unknown endpoints, execution/stop/delete endpoints, and `getExecInfo` (whose `type=DELETE` query can delete records) return `WEB_ENDPOINT_NOT_READ_ONLY`; enabling execution does not override this web policy. Encoded or ambiguous path segments, matrix parameters, and routing override headers are rejected. Query escapes are preserved, redirects remain disabled, and credentials stay server-side.

Applied mutation transactions are retained in the current MCP session for up to one hour, with a limit of 100 transactions and 32 MiB of protected backups. Existing definitions are backed up beneath `.hop-mcp/backups/`; MCP responses return `backup: "protected"`, not the local backup path. The server first attempts an atomic filesystem move. `atomic_replace_used` reports whether that move succeeded; when the filesystem does not support it, the server uses its replacement fallback and still reloads the written definition through Hop, attempting recovery if validation fails. Rollback requires the transaction ID and the current definition SHA-256.

Error categories are `VALIDATION`, `AUTHORIZATION`, `NOT_FOUND`, `CONFLICT`, `PRECONDITION_FAILED`, `TIMEOUT`, `EXECUTION`, `UNSUPPORTED`, `SECURITY`, and `INTERNAL`. A stale SHA-256 is a retryable precondition failure; a semantic entity that already exists is a conflict. Error categories are machine-readable, while tool annotations remain advisory hints.

## Semantic authoring and execution

Use `hop_component_types` and `hop_component_schema` to discover native plugin metadata, prepare an immutable correction plan, review its preview, and explicitly apply it. Plans are SHA-bound, single-use, expire, and never apply automatically. Mutation is transactional and can be rolled back with the returned transaction identifier and current file hash.

Execution is local, bounded, and disabled by default. Use `hop_start_execution`, `hop_execution_status`, `hop_stop_execution`, and `hop_logs` only when `--allow-execution` was explicitly enabled. `hop_execution_history`, `hop_execution_detail`, `hop_execution_children`, and `hop_execution_metrics` read the named native Apache Hop Execution Information Location; they do not create a parallel session history or execution database. The native location name is required because it may be a local file, execution database, Hop Server location, or another Hop plugin.

Asynchronous status includes `native_worker_active`, which tracks the connector's queued/running native worker separately from the caller waiting for its result. Cancellation or timeout keeps the operation in `stopping` and retains its concurrency slot until both have exited; `finished_at` is set only then. A pending timeout may already include `result.timed_out: true`. Poll until a terminal state instead of treating a cancellation request or a cancelled Java future as completion. Native calls that ignore interruption can keep a slot occupied. This field does not track every thread or JDBC statement spawned by Hop plugins, and `false` does not certify that all external work ended.

Execution summaries preserve the native `parent_id` and also expose `launched_by_id`, `owner_execution_id`, and `parent_relationship`. Hop 2.20 records with an explicit launcher distinguish their owning execution from the launching action/transform. A parent-only record remains `unresolved`, even on a newer runtime: old documents can still use the component ID as their parent. The connector does not infer an owner from runtime version alone. A missing optional launcher API is supported on Hop 2.19; a present but failing API returns `EXECUTION_RELATIONSHIP_UNAVAILABLE`.

Child traversal indexes at most 1,000 native execution IDs plus the requested root, reads state only for returned children, and retains the 200-node/32-depth output bounds. Each child includes `via_execution_id` and a `relationship` of `parent` or `launcher`, identifying the edge used for its depth. `scanned`, `scan_truncated`, and `truncated` distinguish an incomplete native scan from a complete small tree. Native location implementations may perform their own internal work while answering bounded API calls; the connector does not claim to control their storage or JDBC implementation.

## Production ETL operations

The metadata tools use Apache Hop's native metadata provider, serializers, and metadata plugin registry. `hop_metadata_types` discovers available metadata types; `hop_metadata_list` and `hop_metadata_get` inspect named objects; and `hop_metadata_dependencies` finds bounded references from project definitions to a metadata object. Metadata values are projected through Hop's `@HopMetadataProperty` model and secret fields are redacted before both `structuredContent` and text content are returned.

`hop_test_connection` is an explicitly authorized deep-check for native `rdbms` metadata. It applies bounded Hop connection/socket timeout variables, uses the native `DatabaseMeta.testConnectionSuccess` path, returns only a bounded redacted diagnostic, and is absent from `tools/list` unless `--allow-deep-check` is enabled.

`hop_schema_compare` is an explicitly authorized deep-check for native `rdbms` table metadata. It uses Hop's `Database.getTableFieldsMeta` path, compares bounded expected columns by name and type/size/nullability attributes, reports `COLUMN_ADDED`, `COLUMN_REMOVED`, `TYPE_CHANGED`, `LENGTH_CHANGED`, `PRECISION_CHANGED`, `SCALE_CHANGED`, and `NULLABILITY_CHANGED`, and never emits DDL or changes data.

`hop_definition_diff` compares two project-relative `.hpl` or `.hwf` files through native `PipelineMeta` or `WorkflowMeta` objects. It reports components added, removed or changed by semantic property name, hop changes, parameter property changes, definition metadata changes, and native project references. It never performs a line-by-line XML diff, never returns configuration values, and never writes either definition.

`hop_impact_analysis` builds a bounded project-local graph from exactly one table, metadata or definition selector. It combines project dependencies, metadata matches, table references, pipeline/workflow references and structural component lineage. `max_depth`, `max_edges` and `max_results` make the traversal explicit; unresolved or out-of-root references are omitted and truncation is reported.

`hop_metadata_dependencies` and `hop_impact_analysis` share one incremental definition index. It carries filesystem attributes from the bounded walk, uses size/mtime/file key as a change stamp, and reparses only changed or invalidated definitions. Concurrent readers share a generation-aware refresh; a mutation during a refresh remains dirty for the next published generation. Typed references prefer native Hop dependencies, then `@HopMetadataProperty` values, then clearly marked exact-leaf XML fallback. The fallback does not search SQL, descriptions, notes, or component names.

`hop_runtime_metrics` is read-only and enabled by default. It returns instance-local index counters and last refresh measurements, plus connection/schema worker counters and a `HEALTHY`/`DEGRADED` state shared by the classloader/JVM. A cancelled call that still runs keeps the worker degraded; new connection/schema checks receive retryable `DEEP_CHECK_WORKER_UNHEALTHY` until it exits. These worker counters do not automatically include `hop_deep_check` or execution. The JDBC global-state guard restores its changes when a call exits, but cannot isolate concurrent changes made by other Hop operations. Metrics do not include project content, environment variables, connection details, or arbitrary system properties.

`hop_resolve_configuration` reads a native pipeline/workflow run configuration for a project-relative `.hpl` or `.hwf` path, applies bounded parameters, resolves values through the project variable hierarchy, reports unresolved references, and redacts variables whose names or references indicate secrets. It does not expose the process environment or mutate metadata. `hop_data_profile` reads only stored `ExecutionData` rows from the selected native location, with bounded fields, rows, distinct tracking and samples; it never runs a pipeline or performs an arbitrary source scan.

`hop_environment_diff` compares two project-relative definitions of the same kind together with their native run configurations. It reports only bounded non-sensitive changes in definition variables, metadata references, run-configuration properties and parameter defaults; unresolved variable names are explicit, while secret values remain redacted on both sides. It never contacts databases or executes a pipeline/workflow.

`hop_diagnose_execution` is an explicitly authorized evidence aggregator. It reads one native execution, bounded component metrics and previous executions, optionally consumes a supplied redacted log channel, and correlates definition metadata, connection references, parameter defaults and the native run configuration. `facts` are observed values; `possible_causes` are always marked unverified; and `recommendations` never apply a correction automatically. Missing evidence is reported rather than inferred.

Connection tests and schema comparison save and restore `DriverManager.loginTimeout` around their JDBC operation, including exceptional exits. That setting is JVM-global: a concurrent Hop operation outside this plugin can still change it, and an underlying JDBC call that ignores interruption may keep running after the MCP request times out. In that case restoration occurs when the call actually returns.

Example requests include `{"type":"rdbms","name":"DWH_PROD"}` for a connection, `{"connection":"DWH_PROD","schema":"public","table":"customers","expected":[{"name":"id","type":"Integer","length":10,"nullable":false}]}` for schema comparison, `{"path_a":"pipelines/load_sales_old.hpl","path_b":"pipelines/load_sales.hpl"}` for semantic definition diff, `{"table":"DWH.DIM_SITE","max_depth":10,"max_edges":100,"max_results":50}` for impact analysis, `{"path_a":"pipelines/load_sales_dev.hpl","path_b":"pipelines/load_sales_prod.hpl","run_configuration_a":"Local-DEV","run_configuration_b":"Local-PROD"}` for environment comparison, `{"location":"EXECUTION_DB","execution_id":"...","channel_id":"...","max_previous":10}` for evidence-based diagnosis, `{"type":"pipeline-run-configuration","query":"prod"}` for run configurations, and `{"path":"pipelines/load_sales.hpl","run_configuration":"Local-PROD","parameters":{"FECHA_INICIO":"2026-09-01"}}` for effective resolution. Native execution reads require `location` and `execution_id`; connection testing and schema comparison remain deep-check gated. Configuration resolution, semantic diff, impact analysis and environment comparison are read-only. Diagnosis and stored execution-data profiling require `--allow-execution` because they can expose operational execution evidence or sampled row values. Field lineage remains intentionally unavailable until Hop exposes a reliable native field-mapping contract.

### Performance characteristics

`2.2.1` adds an incremental in-memory project-definition index shared by metadata dependency and impact analysis. Unchanged `.hpl`/`.hwf` definitions are reused based on bounded filesystem metadata, while changed definitions are reparsed individually. Execution history also stops once the requested page is satisfied, diagnosis reuses a single Execution Information Location session, stored profiling has global work budgets, and authorized deep checks share a bounded worker.

A same-runner synthetic benchmark on GitHub Actions (Java 21.0.12.1, 4 available cores, approximately 4 GiB max heap) compared `v2.2.0` with `2.2.1` across 1,000, 2,500, and 5,000 definitions. At 5,000 definitions, repeated warm impact analysis improved from 944 ms to 92 ms (about 10.3x), and re-analysis after changing one unrelated definition improved from 942 ms to 98 ms (about 9.6x). The initial cold 5,000-definition index build increased from 959 ms to 1,346 ms because `2.2.1` builds the reusable index. These figures are synthetic regression evidence, not a universal latency guarantee.

`2.2.2` carries forward the bounded index with single-flight refresh, generation-aware invalidation, immutable snapshots and cached typed metadata references. Definition scans can examine up to 50,000 regular files while retaining at most 5,000 definitions, so unrelated files before Hop definitions no longer consume the definition result budget. The shared deep-check worker remains one thread with a bounded queue; after a timed-out operation that ignores interruption, metrics report `DEGRADED` until its work exits.

Definition cache stamps use size, last-modified time and `fileKey` when the filesystem provides one. `fileKey` may be null, and the stamp is a change-detection hint rather than a content-integrity guarantee; warm snapshots do not hash every definition.

The separate [project-index benchmark workflow](.github/workflows/project-index-benchmark.yml) runs manually or weekly. It selects the newest compatible published stable ancestor release, or an explicit `baseline_tag`, and compares it with the selected commit on one runner and shared synthetic fixtures, then emits `benchmark.json`, `benchmark.csv`, and a Job Summary. Latency is informational; correctness checks cover cold/warm reuse, one-file changes, the 64-node impact chain, mixed files, definition bounds, and truncation.

For distribution-level measurements, the manual Windows [full-Hop benchmark](docs/full-hop-benchmark.md) compares the selected published baseline with the development ZIP using alternating fresh JVMs and identical result hashes. It stores raw reports and provenance outside `target/`, separately from the synthetic Maven benchmark. This synthetic matrix does not certify third-party plugins or replace acceptance on real projects.

## Live UI

The optional Tools menu adapter synchronizes native Desktop or Hop Web/RAP sessions with same-project semantic changes. It is session-scoped, protects dirty tabs, uses bounded project-local events in `.hop-mcp/`, and opens no network listener.

## Development and validation

STDIO uses the native SDK 2.1.0 transport, including its concurrent-send correction. The SDK continues to own JSON-RPC encoding, STDIO I/O and session handling. A regression test sends 32 requests without waiting for their responses, mixes successful calls with schema errors, and verifies that the session remains usable. Timeout diagnostics are bounded and captured stdout is redacted before inclusion in test failures.

```bash
mvn -B clean verify
mvn -B -P hop-2.20 clean verify
```

The STDIO integration tests cover initialization, the 2025-11-25 handshake, `notifications/initialized`, tool discovery, consecutive calls, schema errors, unknown tools, handler failures, EOF, enabled execution, mutation and rollback, and the correction-plan lifecycle. They validate the advertised output schemas that are exercised locally with the MCP SDK JSON Schema validator, and use a local HTTP fixture for the web response and redaction path. Native execution-location reads are also covered with bounded repository fixtures and unknown-location error paths. CI is configured to install the plugin into a clean Hop 2.19.0 distribution, exercise `hop_config` and `hop_validate`, compare typed native references for seven real database transform plugins, and run operational MCP acceptance on disposable local fixtures, then build separately against 2.20.0-SNAPSHOT. Linux confinement tests must execute without skips. See [production verification](docs/production-verification.md) for scope, evidence and remaining release conditions.

The `hop-2.20` profile also loads real Watch Files, OpenFormula, Pipeline Executor, Table Input and Database Join plugins in tests. It verifies semantic round trips and cancellation/timeout of running H2 queries through the MCP service. These plugin and H2 dependencies are test-only and are not bundled in the Marketplace ZIP. The seven component/JDBC tests are skipped in the stable profile; their results do not establish the same behavior on Hop 2.19 or other JDBC drivers. See [upstream implementation status](docs/hop-upstream-implementation.md) for completed work, validation evidence and remaining linter/cancellation limits.

## MCP Conformance

Production remains STDIO (`hop mcp`). Conformance uses a test-only adapter built on the MCP Java SDK's official `HttpServletStreamableServerTransportProvider` and embedded Tomcat. It binds only to `127.0.0.1`, uses a dynamic port, and reuses the same server definition, tool registry, handlers, schemas and service as STDIO; it is not a supported production HTTP transport.

The CI job `mcp-conformance-2025-11-25` pins `@modelcontextprotocol/conformance@0.2.0-alpha.11` because that runner provides the frozen `--requirements 2025-11-25` set. CI records the requirement list and runs the official requirement-set command directly against the test-only HTTP adapter.

The official 2025-11-25 requirement set is an "everything-server" conformance profile: several scored scenarios require named conformance fixture tools such as `test_simple_text`, plus prompts, resources, completion, sampling and elicitation behaviors. Those are not production capabilities of this Apache Hop connector. The repository therefore uses the runner's official `--expected-failures` mechanism in `.github/mcp-conformance-2025-11-25-baseline.yml` for those known gaps instead of adding fake product capabilities or maintaining a second scorer.

The baseline does not convert those scenarios into conformance passes: they remain documented failures against the full requirement set. It is a regression guard. Any new unlisted failure fails CI, and any baselined scenario that starts passing makes the baseline stale and also fails CI until the entry is removed. Core scenarios that the connector actually exercises, including initialization, ping, logging level handling, tool listing, the Streamable HTTP transport checks reached by the requirement set, and DNS-rebinding protection, are not baselined and must pass normally.

CI additionally inspects the raw `checks.json` reports with `scripts/ci/check-conformance-results.py`, independently of the runner's exit code. It rejects missing/unexecuted core scenarios, unlisted failures, stale baselines and wire-schema violations even inside a baselined fixture scenario. The baseline explicitly includes the runner's `json_schema_2020_12_tool` fixture requirement; this does not add a fake production tool. The test-only HTTP adapter flushes idle GET event-stream headers immediately so resumability probes can start their bounded read/cancellation timers. Production remains STDIO.

The report is uploaded as the `mcp-conformance-2025-11-25` CI artifact. Production remains STDIO; the HTTP adapter exists only for conformance testing and binds explicitly to `127.0.0.1`.

The CI workflow also checks the Marketplace ZIP layout, license and notice files, Jandex index, and absence of bundled Hop runtime jars, and produces a CycloneDX SBOM. Tag-triggered releases validate the `vMAJOR.MINOR.PATCH` tag against the POM and verified artifact, generate SHA-256 checksums, and publish provenance and SBOM attestations for the release ZIP through GitHub's attestation service. The GitHub Release receives the ZIP, SBOM files, and checksum list. Workflow configuration is not itself evidence that a particular run succeeded.

## Registry metadata

No official MCP Registry `server.json` is included: the registry's current package types do not include Apache Hop Marketplace ZIPs. MCPB is a separate package format and is not used to label this Marketplace artifact.

Inspect the release package with `unzip -l target/hop-mcp-connector-2.2.6.zip`; it must contain `plugins/misc/hop-mcp-connector/`, `LICENSE`, and `NOTICE`, and must not contain Hop runtime jars.

## Security reporting

See [`SECURITY.md`](SECURITY.md). Do not include production credentials, private configuration, or customer data in public reports.

## License and trademarks

Licensed under the Apache License 2.0; see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE). Apache Hop and Apache are trademarks of the Apache Software Foundation. Use of those names describes compatibility only and does not imply endorsement or affiliation.
