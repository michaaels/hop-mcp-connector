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
- MCP Java SDK 2.0.1; protocol baseline 2025-11-25 over STDIO.

| Connector | Apache Hop | Java | MCP Java SDK | MCP revision |
|---|---|---:|---:|---|
| 2.0.0 | 2.19.0 compile baseline; 2.20.0-SNAPSHOT profile | 21 | 2.0.1 | 2025-11-25 |
| 2.1.0 | 2.19.0 compile baseline; 2.20.0-SNAPSHOT profile | 21 | 2.0.1 | 2025-11-25 |
| 2.2.0 | 2.19.0 compile baseline; 2.20.0-SNAPSHOT profile | 21 | 2.0.1 | 2025-11-25 |
| 2.2.1 | 2.19.0 compile baseline; 2.20.0-SNAPSHOT profile | 21 | 2.0.1 | 2025-11-25 |
| 2.2.2 | 2.19.0 compile baseline; 2.20.0-SNAPSHOT profile | 21 | 2.0.1 | 2025-11-25 |

The MCP Java SDK 2.0.1 supports protocol revision `2025-11-25`. Revision `2026-07-28` is not supported by this SDK line and is not implemented here. Apache Hop 2.19.0 is the stable compile baseline; the `hop-2.20` profile is a compatibility check against the 2.20.0-SNAPSHOT line, not a stable-support promise. The CI workflow is configured to verify the 2.19.0 archive checksum, install the Marketplace ZIP into a clean distribution, and exercise `hop mcp` over STDIO, as well as build against the 2.20.0-SNAPSHOT line. Installation through the published Hop Marketplace catalog requires post-release verification.

## Installation

Build the Marketplace ZIP and CycloneDX SBOM with `mvn -B clean verify`. Version 2.2.2 builds `target/hop-mcp-connector-2.2.2.zip`. SBOM files are `target/bom.json` and `target/bom.xml`. Install the ZIP into Hop's `plugins/misc/hop-mcp-connector/` directory or use the repository/catalog metadata in `marketplace/`. Restart Hop, then run `hop mcp --help`.

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

`hop_read_text` returns at most 128 KiB per call (64 KiB by default). Its offsets and byte counts refer to the UTF-8 redacted text view, so callers can request the next chunk without splitting a secret at a chunk boundary. Search redacts file content before matching and returning snippets. Hop Web reads at most 4 MiB and returns at most 64 KiB of response body after redaction.

Applied mutation transactions are retained in the current MCP session for up to one hour, with a limit of 100 transactions and 32 MiB of protected backups. Existing definitions are backed up beneath `.hop-mcp/backups/`; MCP responses return `backup: "protected"`, not the local backup path. The server first attempts an atomic filesystem move. `atomic_replace_used` reports whether that move succeeded; when the filesystem does not support it, the server uses its replacement fallback and still reloads the written definition through Hop, attempting recovery if validation fails. Rollback requires the transaction ID and the current definition SHA-256.

Error categories are `VALIDATION`, `AUTHORIZATION`, `NOT_FOUND`, `CONFLICT`, `PRECONDITION_FAILED`, `TIMEOUT`, `EXECUTION`, `UNSUPPORTED`, `SECURITY`, and `INTERNAL`. A stale SHA-256 is a retryable precondition failure; a semantic entity that already exists is a conflict. Error categories are machine-readable, while tool annotations remain advisory hints.

## Semantic authoring and execution

Use `hop_component_types` and `hop_component_schema` to discover native plugin metadata, prepare an immutable correction plan, review its preview, and explicitly apply it. Plans are SHA-bound, single-use, expire, and never apply automatically. Mutation is transactional and can be rolled back with the returned transaction identifier and current file hash.

Execution is local, bounded, and disabled by default. Use `hop_start_execution`, `hop_execution_status`, `hop_stop_execution`, and `hop_logs` only when `--allow-execution` was explicitly enabled. `hop_execution_history`, `hop_execution_detail`, `hop_execution_children`, and `hop_execution_metrics` read the named native Apache Hop Execution Information Location; they do not create a parallel session history or execution database. The native location name is required because it may be a local file, execution database, Hop Server location, or another Hop plugin.

## Production ETL operations

The metadata tools use Apache Hop's native metadata provider, serializers, and metadata plugin registry. `hop_metadata_types` discovers available metadata types; `hop_metadata_list` and `hop_metadata_get` inspect named objects; and `hop_metadata_dependencies` finds bounded references from project definitions to a metadata object. Metadata values are projected through Hop's `@HopMetadataProperty` model and secret fields are redacted before both `structuredContent` and text content are returned.

`hop_test_connection` is an explicitly authorized deep-check for native `rdbms` metadata. It applies bounded Hop connection/socket timeout variables, uses the native `DatabaseMeta.testConnectionSuccess` path, returns only a bounded redacted diagnostic, and is absent from `tools/list` unless `--allow-deep-check` is enabled.

`hop_schema_compare` is an explicitly authorized deep-check for native `rdbms` table metadata. It uses Hop's `Database.getTableFieldsMeta` path, compares bounded expected columns by name and type/size/nullability attributes, reports `COLUMN_ADDED`, `COLUMN_REMOVED`, `TYPE_CHANGED`, `LENGTH_CHANGED`, `PRECISION_CHANGED`, `SCALE_CHANGED`, and `NULLABILITY_CHANGED`, and never emits DDL or changes data.

`hop_definition_diff` compares two project-relative `.hpl` or `.hwf` files through native `PipelineMeta` or `WorkflowMeta` objects. It reports components added, removed or changed by semantic property name, hop changes, parameter property changes, definition metadata changes, and native project references. It never performs a line-by-line XML diff, never returns configuration values, and never writes either definition.

`hop_impact_analysis` builds a bounded project-local graph from exactly one table, metadata or definition selector. It combines project dependencies, metadata matches, table references, pipeline/workflow references and structural component lineage. `max_depth`, `max_edges` and `max_results` make the traversal explicit; unresolved or out-of-root references are omitted and truncation is reported.

`hop_metadata_dependencies` and `hop_impact_analysis` share one incremental definition index. It carries filesystem attributes from the bounded walk, uses size/mtime/file key as a change stamp, and reparses only changed or invalidated definitions. Concurrent readers share a generation-aware refresh; a mutation during a refresh remains dirty for the next published generation. Typed references prefer native Hop dependencies, then `@HopMetadataProperty` values, then clearly marked exact-leaf XML fallback. The fallback does not search SQL, descriptions, notes, or component names.

`hop_runtime_metrics` is read-only and enabled by default. It returns aggregate index counters, the last refresh measurements, deep-check queue counters, and a `HEALTHY`/`DEGRADED` worker state. It does not include project content, environment variables, connection details, or arbitrary system properties.

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

The separate [project-index benchmark workflow](.github/workflows/project-index-benchmark.yml) runs manually or weekly. It compares `v2.2.1` with the selected `main` commit on one runner and shared synthetic fixtures, then emits `benchmark.json`, `benchmark.csv`, and a Job Summary. Latency is informational; correctness checks cover cold/warm reuse, one-file changes, the 64-node impact chain, mixed files, definition bounds, and truncation.

## Live UI

The optional Tools menu adapter synchronizes native Desktop or Hop Web/RAP sessions with same-project semantic changes. It is session-scoped, protects dirty tabs, uses bounded project-local events in `.hop-mcp/`, and opens no network listener.

## Development and validation

```bash
mvn -B clean verify
mvn -B -P hop-2.20 clean verify
```

The STDIO integration tests cover initialization, the 2025-11-25 handshake, `notifications/initialized`, tool discovery, consecutive calls, schema errors, unknown tools, handler failures, EOF, enabled execution, mutation and rollback, and the correction-plan lifecycle. They validate the advertised output schemas that are exercised locally with the MCP SDK JSON Schema validator, and use a local HTTP fixture for the web response and redaction path. Native execution-location reads are also covered with bounded repository fixtures and unknown-location error paths. CI is configured to install the plugin into a clean Hop 2.19.0 distribution and exercise `hop_config` and `hop_validate`, then build separately against 2.20.0-SNAPSHOT.

## MCP Conformance

Production remains STDIO (`hop mcp`). Conformance uses a test-only adapter built on the MCP Java SDK's official `HttpServletStreamableServerTransportProvider` and embedded Tomcat. It binds only to `127.0.0.1`, uses a dynamic port, and reuses the same server definition, tool registry, handlers, schemas and service as STDIO; it is not a supported production HTTP transport.

The CI job `mcp-conformance-2025-11-25` pins `@modelcontextprotocol/conformance@0.2.0-alpha.11` because that runner provides the frozen `--requirements 2025-11-25` set. CI records the requirement list and runs the official requirement-set command directly against the test-only HTTP adapter.

The official 2025-11-25 requirement set is an "everything-server" conformance profile: several scored scenarios require named conformance fixture tools such as `test_simple_text`, plus prompts, resources, completion, sampling and elicitation behaviors. Those are not production capabilities of this Apache Hop connector. The repository therefore uses the runner's official `--expected-failures` mechanism in `.github/mcp-conformance-2025-11-25-baseline.yml` for those known gaps instead of adding fake product capabilities or maintaining a second scorer.

The baseline does not convert those scenarios into conformance passes: they remain documented failures against the full requirement set. It is a regression guard. Any new unlisted failure fails CI, and any baselined scenario that starts passing makes the baseline stale and also fails CI until the entry is removed. Core scenarios that the connector actually exercises, including initialization, ping, logging level handling, tool listing, the Streamable HTTP transport checks reached by the requirement set, and DNS-rebinding protection, are not baselined and must pass normally.

The report is uploaded as the `mcp-conformance-2025-11-25` CI artifact. Production remains STDIO; the HTTP adapter exists only for conformance testing and binds explicitly to `127.0.0.1`.

The CI workflow also checks the Marketplace ZIP layout, license and notice files, Jandex index, and absence of bundled Hop runtime jars, and produces a CycloneDX SBOM. Tag-triggered releases validate the `vMAJOR.MINOR.PATCH` tag against the POM and verified artifact, generate SHA-256 checksums, and publish provenance and SBOM attestations for the release ZIP through GitHub's attestation service. The GitHub Release receives the ZIP, SBOM files, and checksum list. Workflow configuration is not itself evidence that a particular run succeeded.

## Registry metadata

No official MCP Registry `server.json` is included: the registry's current package types do not include Apache Hop Marketplace ZIPs. MCPB is a separate package format and is not used to label this Marketplace artifact.

Inspect the release package with `unzip -l target/hop-mcp-connector-2.2.2.zip`; it must contain `plugins/misc/hop-mcp-connector/`, `LICENSE`, and `NOTICE`, and must not contain Hop runtime jars.

## Security reporting

See [`SECURITY.md`](SECURITY.md). Do not include production credentials, private configuration, or customer data in public reports.

## License and trademarks

Licensed under the Apache License 2.0; see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE). Apache Hop and Apache are trademarks of the Apache Software Foundation. Use of those names describes compatibility only and does not imply endorsement or affiliation.
