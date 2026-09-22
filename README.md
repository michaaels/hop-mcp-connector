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
- Transactional semantic mutation with preview, SHA-256 preconditions, backup, atomic replacement, native reload validation, automatic recovery, and explicit rollback.
- Bounded inputs, scans, results, execution and logs; project-root confinement; secure XML parsing; sensitive-data redaction.
- Optional Hop Desktop and Hop Web live synchronization, explicitly started from the UI and without a network listener.

## Security defaults

Inspection and structural validation are available by default. Deep checks, execution, authoring/mutation, and Hop Web tools are omitted from `tools/list` unless their respective flags are enabled. The service layer also checks authorization before deep checks, execution, writes, and web requests.

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

The MCP Java SDK 2.0.1 supports protocol revision `2025-11-25`. Revision `2026-07-28` is not supported by this SDK line and is not implemented here. Apache Hop 2.19.0 is the current stable compile baseline. The `hop-2.20` profile has been verified against the 2.20.0-SNAPSHOT line. CI downloads the checksum-verified Hop baseline, installs the Marketplace ZIP into a clean distribution, and exercises `hop mcp` over STDIO. Installation through the published Hop Marketplace catalog requires post-release verification.

## Installation

Build the Marketplace ZIP and CycloneDX SBOM with `mvn -B clean verify`. The 2.0.0 artifact is `target/hop-mcp-connector-2.0.0.zip`; SBOM files are `target/bom.json` and `target/bom.xml`. Install the ZIP into Hop's `plugins/misc/hop-mcp-connector/` directory or use the repository/catalog metadata in `marketplace/`. Restart Hop, then run `hop mcp --help`.

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

Tools carry MCP annotations as client hints, not authorization. Strict bounded success and error output schemas are defined for `hop_config`, `hop_capabilities`, `hop_validate`, `hop_inspect`, `hop_catalog`, `hop_component_types`, `hop_component_schema`, `hop_execute`, `hop_execution_status`, `hop_mutate_definition`, and `hop_prepare_correction_plan`. Other tools still need output schemas. Results include both `structuredContent` and JSON `TextContent` for compatibility with clients that only consume text content, as recommended by MCP for backward compatibility.

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

The STDIO integration test covers initialization, the 2025-11-25 handshake, `notifications/initialized`, tool discovery, consecutive calls, schema errors, unknown tools, handler failures, EOF, and real responses from the read-only tools and mutation previews. With execution explicitly enabled, it also calls `hop_component_schema`, `hop_execute`, and `hop_execution_status` against Apache Hop's local pipeline engine. The test validates actual `structuredContent` from all eleven tools with advertised output schemas using the MCP SDK JSON Schema validator. The clean-install CI smoke additionally starts Hop 2.19.0 with this ZIP installed and calls `hop_config` and `hop_validate`. The official MCP Conformance Suite is not wired into CI. Its current runner tests Streamable HTTP, while production transport here is STDIO; its `2025-11-25` required scenarios also include prompts, resources, completion, logging, sampling, elicitation, and SSE, which this tools-only STDIO server does not advertise. The Java SDK reference fixture also uses conformance-specific tools and resources. An HTTP adapter alone would not satisfy those scenarios, so no conformance pass is claimed.

## Registry metadata

No official MCP Registry `server.json` is included: the registry's current package types do not include Apache Hop Marketplace ZIPs. MCPB is a separate package format and is not used to label this Marketplace artifact.

Inspect the generated package with `unzip -l target/hop-mcp-connector-2.0.0.zip`; it must contain `plugins/misc/hop-mcp-connector/`, `LICENSE`, and `NOTICE`, and must not contain Hop runtime jars.

## Security reporting

See [`SECURITY.md`](SECURITY.md). Do not include production credentials, private configuration, or customer data in public reports.

## License and trademarks

Licensed under the Apache License 2.0; see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE). Apache Hop and Apache are trademarks of the Apache Software Foundation. Use of those names describes compatibility only and does not imply endorsement or affiliation.
