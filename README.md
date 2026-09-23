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

The MCP Java SDK 2.0.1 supports protocol revision `2025-11-25`. Revision `2026-07-28` is not supported by this SDK line and is not implemented here. Apache Hop 2.19.0 is the stable compile baseline; the `hop-2.20` profile is a compatibility check against the 2.20.0-SNAPSHOT line, not a stable-support promise. The CI workflow is configured to verify the 2.19.0 archive checksum, install the Marketplace ZIP into a clean distribution, and exercise `hop mcp` over STDIO, as well as build against the 2.20.0-SNAPSHOT line. Installation through the published Hop Marketplace catalog requires post-release verification.

## Installation

Build the Marketplace ZIP and CycloneDX SBOM with `mvn -B clean verify`. The 2.1.0 artifact is `target/hop-mcp-connector-2.1.0.zip`; SBOM files are `target/bom.json` and `target/bom.xml`. Install the ZIP into Hop's `plugins/misc/hop-mcp-connector/` directory or use the repository/catalog metadata in `marketplace/`. Restart Hop, then run `hop mcp --help`.

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
| Inspect | `hop_plugins`, `hop_catalog`, `hop_list_definitions`, `hop_inspect`, `hop_context`, `hop_component`, `hop_component_lineage`, `hop_read_text`, `hop_search`, `hop_find_table`, `hop_dependencies` | enabled |
| Validate | `hop_validate` | enabled |
| Deep check | `hop_deep_check` | `--allow-deep-check` |
| Author | `hop_component_types`, `hop_component_schema`, `hop_prepare_correction_plan`, `hop_apply_correction_plan`, `hop_correction_plan_status`, `hop_mutate_definition`, `hop_rollback_mutation` | `--allow-mutation` |
| Execute | `hop_test_definition`, `hop_execute`, `hop_start_execution`, `hop_execution_status`, `hop_stop_execution`, `hop_logs` | `--allow-execution`; the optional deep-check phase also requires `--allow-deep-check` |
| Web | `hop_web_request` | `--allow-web-api` |

Tools carry MCP annotations as client hints, not authorization. Strict bounded success and error output schemas are advertised for 30 tools: `hop_config`, `hop_capabilities`, `hop_live_ui_status`, `hop_context`, `hop_validate`, `hop_inspect`, `hop_catalog`, `hop_list_definitions`, `hop_read_text`, `hop_search`, `hop_find_table`, `hop_dependencies`, `hop_component`, `hop_component_lineage`, `hop_plugins`, `hop_component_types`, `hop_component_schema`, `hop_execute`, `hop_start_execution`, `hop_stop_execution`, `hop_execution_status`, `hop_deep_check`, `hop_test_definition`, `hop_logs`, `hop_web_request`, `hop_mutate_definition`, `hop_prepare_correction_plan`, `hop_apply_correction_plan`, `hop_correction_plan_status`, and `hop_rollback_mutation`. Other tools still return bounded JSON-compatible results but do not advertise an output schema. Tool failures use an error object with `code`, `category`, `message`, and `retryable`; messages are sanitized. Successful results include both `structuredContent` and JSON `TextContent` for clients that consume text content.

## Bounds, pagination, and errors

Project file reads are limited to 4 MiB per file. Project scans stop at 50,000 visited entries, 5,000 scanned files, or depth 64; content scans and catalog hashing also have a 32 MiB byte budget. Structured pages contain at most 200 rows and the complete tool response is limited to 512 KiB. Results include count-completeness and truncation indicators where applicable, so `count` may describe only the bounded scan. The internal `.hop-mcp/` directory is excluded from project tools.

`hop_read_text` returns at most 128 KiB per call (64 KiB by default). Its offsets and byte counts refer to the UTF-8 redacted text view, so callers can request the next chunk without splitting a secret at a chunk boundary. Search redacts file content before matching and returning snippets. Hop Web reads at most 4 MiB and returns at most 64 KiB of response body after redaction.

Applied mutation transactions are retained in the current MCP session for up to one hour, with a limit of 100 transactions and 32 MiB of protected backups. Existing definitions are backed up beneath `.hop-mcp/backups/`; MCP responses return `backup: "protected"`, not the local backup path. The server first attempts an atomic filesystem move. `atomic_replace_used` reports whether that move succeeded; when the filesystem does not support it, the server uses its replacement fallback and still reloads the written definition through Hop, attempting recovery if validation fails. Rollback requires the transaction ID and the current definition SHA-256.

Error categories are `VALIDATION`, `AUTHORIZATION`, `NOT_FOUND`, `CONFLICT`, `PRECONDITION_FAILED`, `TIMEOUT`, `EXECUTION`, `UNSUPPORTED`, `SECURITY`, and `INTERNAL`. A stale SHA-256 is a retryable precondition failure; a semantic entity that already exists is a conflict. Error categories are machine-readable, while tool annotations remain advisory hints.

## Semantic authoring and execution

Use `hop_component_types` and `hop_component_schema` to discover native plugin metadata, prepare an immutable correction plan, review its preview, and explicitly apply it. Plans are SHA-bound, single-use, expire, and never apply automatically. Mutation is transactional and can be rolled back with the returned transaction identifier and current file hash.

Execution is local, bounded, and disabled by default. Use `hop_start_execution`, `hop_execution_status`, `hop_stop_execution`, and `hop_logs` only when `--allow-execution` was explicitly enabled.

## Live UI

The optional Tools menu adapter synchronizes native Desktop or Hop Web/RAP sessions with same-project semantic changes. It is session-scoped, protects dirty tabs, uses bounded project-local events in `.hop-mcp/`, and opens no network listener.

## Development and validation

```bash
mvn -B clean verify
mvn -B -P hop-2.20 clean verify
```

The STDIO integration tests cover initialization, the 2025-11-25 handshake, `notifications/initialized`, tool discovery, consecutive calls, schema errors, unknown tools, handler failures, EOF, enabled execution, mutation and rollback, and the correction-plan lifecycle. They validate actual results for all 30 advertised output schemas with the MCP SDK JSON Schema validator, and use a local HTTP fixture for the web response and redaction path. CI is configured to install the plugin into a clean Hop 2.19.0 distribution and exercise `hop_config` and `hop_validate`, then build separately against 2.20.0-SNAPSHOT.

## MCP Conformance

Production remains STDIO (`hop mcp`). Conformance uses a test-only adapter built on the MCP Java SDK's official `HttpServletStreamableServerTransportProvider` and embedded Tomcat. It binds only to `127.0.0.1`, uses a dynamic port, and reuses the same server definition, tool registry, handlers, schemas and service as STDIO; it is not a supported production HTTP transport.

The CI job `mcp-conformance-2025-11-25` pins `@modelcontextprotocol/conformance@0.2.0-alpha.11`. The latest stable npm package observed during validation was `0.1.16`, which does not implement `--requirements`; the pinned alpha is used because it provides the frozen requirement-set command. CI records `list --requirements 2025-11-25`, runs the exact requirement-set command with a global timeout, and then runs each required server scenario individually so pending or added-after-release scenarios cannot hang the scored gate. It does not use `--expected-failures`.

The 2025-11-25 requirement set lists 30 required server scenarios. It reports `server-session-lifecycle` as `added-after-release`, and `json-schema-2020-12` plus `server-sse-polling` as `pending`; these are informational. This connector intentionally advertises tools only, so prompts, resources, completion, sampling, elicitation and SSE scenarios are not claimed as product capabilities and are not implemented merely to improve a conformance percentage.

The report is uploaded as the `mcp-conformance-2025-11-25` CI artifact. Local evidence is written below `target/mcp-conformance-2025-11-25/`. During the initial validation, `tools-list` passed 3/3 checks after the output-schema fix; the complete scored result is not claimed as passing because the tools-only capability scope produces expected non-applicable scenario failures, and the Windows runner also ended successful checks with a Node/UV process assertion.

The CI workflow also checks the Marketplace ZIP layout, license and notice files, Jandex index, and absence of bundled Hop runtime jars, and produces a CycloneDX SBOM. Tag-triggered releases validate the `vMAJOR.MINOR.PATCH` tag against the POM and verified artifact, generate SHA-256 checksums, and publish provenance and SBOM attestations for the release ZIP through GitHub's attestation service. The GitHub Release receives the ZIP, SBOM files, and checksum list. Workflow configuration is not itself evidence that a particular run succeeded.

## Registry metadata

No official MCP Registry `server.json` is included: the registry's current package types do not include Apache Hop Marketplace ZIPs. MCPB is a separate package format and is not used to label this Marketplace artifact.

Inspect the generated package with `unzip -l target/hop-mcp-connector-2.1.0.zip`; it must contain `plugins/misc/hop-mcp-connector/`, `LICENSE`, and `NOTICE`, and must not contain Hop runtime jars.

## Security reporting

See [`SECURITY.md`](SECURITY.md). Do not include production credentials, private configuration, or customer data in public reports.

## License and trademarks

Licensed under the Apache License 2.0; see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE). Apache Hop and Apache are trademarks of the Apache Software Foundation. Use of those names describes compatibility only and does not imply endorsement or affiliation.
