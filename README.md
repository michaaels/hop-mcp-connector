# Apache Hop MCP 1.0.0

Native semantic Model Context Protocol (MCP) server plugin for **Apache Hop 2.19.x and 2.20.x**.

> This is a community project and is not an official Apache Software Foundation project.

## What changed in 1.0.0

Version 1.0.0 promotes the **Apache Hop Native Semantic MCP** to its first stable contract. It combines safe project inspection, gated validation and execution, native semantic authoring, immutable correction plans, transactional writes and optional Desktop/Hop Web live synchronization. A real STDIO integration test now protects the complete MCP initialization handshake and consecutive tool calls. Inspection remains enabled by default; deep checks, execution, writes and Hop Web access require separate command-line flags.

```text
Codex / Claude / Qwen
        |
      MCP STDIO
        |
     hop mcp
        |
  Apache Hop JVM
  + PluginRegistry
  + PipelineMeta / WorkflowMeta
  + local execution engines
```

Installing the plugin once makes both integrations available after restarting Hop:

- `hop mcp` / `hop.bat mcp` — headless MCP server.
- **Tools → Apache Hop MCP live synchronization...** — start or stop the adapter for the current Desktop process or Hop Web browser session.

## Hop Desktop and Hop Web live synchronization

Open the same project in Hop Desktop or Hop Web and select **Tools → Apache Hop MCP live synchronization...**. While that UI session is active, semantic changes made by `hop mcp --allow-mutation` against the same project root are reflected through native Hop UI APIs:

- an already-open definition is reloaded from disk;
- a changed definition that is not open is opened in the Explorer perspective;
- rolling back a newly created definition closes its tab;
- a tab with unsaved changes is never overwritten or closed.

The bridge uses bounded, short-lived control events under `.hop-mcp/`. These files contain only project-relative definition paths, event identifiers and SHA-256 fingerprints. They are excluded from MCP catalog, search, read and mutation tools. Session heartbeats expire automatically after 45 seconds, and events expire after 24 hours.

Live synchronization is explicit and session-scoped. Hop Web creates a separate RAP adapter for each browser session and uses Hop's server-push facade; no plugin code accesses RAP internals directly. If multiple users explicitly subscribe to the same project, each session receives the same project event but applies its own dirty-tab check and acknowledgement. Session identifiers are never returned by MCP tools. The bridge does not expose a network listener and is not a replacement for authentication or transport security.

## Security model

Project inspection is available by default. Pipeline/workflow execution requires `--allow-execution`, is limited to local engine run configurations, permits at most four concurrent operations, and enforces a timeout of at most 900 seconds. Parameters and returned errors/logs are redacted.

Semantic mutation requires `--allow-mutation` when changes are applied. Previews remain read-only. Existing files require an expected SHA-256 precondition. Applied changes use native Hop semantic objects, create a backup, atomically replace the definition, reload it through Hop, automatically restore the backup on validation failure, and return a session transaction ID for explicit rollback. The semantic contract includes component creation and updates through Hop's plugin registry and metadata-injection API, definition metadata, component rename/move/removal, and hop add/remove/state operations. Component authoring accepts bounded scalar properties and one-level tabular groups returned by `hop_component_schema`; secret-looking keys, unknown properties, nested objects and deeper collections are excluded.

The project root is a hard boundary. Paths are normalized and resolved with real paths so traversal and symlinks cannot escape it. XML parsing disables DTDs and external entities. File reads, scans, results, logs, operations, traversal depth, property groups, rows, and cells are bounded. Secret-looking values are redacted.

`hop_deep_check` is disabled unless the server starts with `--allow-deep-check`, because Hop's native checker can resolve fields or contact configured databases/services.

Hop Web access remains read-only and opt-in. `hop_web_request` only permits `GET` and `HEAD`, confines requests to the configured base path, disables redirects, bounds response bodies, and redacts sensitive data. Credentials come from `HOP_MCP_WEB_USERNAME` / `HOP_MCP_WEB_PASSWORD` or `HOP_MCP_WEB_BEARER_TOKEN`; callers cannot supply authentication headers.

## Requirements and build

- Apache Hop **2.19.x** (release baseline) or **2.20.x**
- Java **21**
- MCP client with STDIO support

```bash
mvn -B clean verify
```

Until Hop 2.20.0 is published, compatibility can be checked against a locally installed build of Apache Hop `main`:

```bash
mvn -B -P hop-2.20 clean verify
```

The Marketplace artifact is `target/apache-hop-mcp-1.0.0.zip`, containing:

```text
plugins/misc/apache-hop-mcp/
  apache-hop-mcp-1.0.0.jar
  version.xml
  lib/...
```

Apache Hop jars are `provided` and are not bundled.

## Marketplace installation

After the `v1.0.0` GitHub Release exists, import `marketplace/hop-marketplace-repo.yaml` into Hop Marketplace and install **Apache Hop MCP**, or use:

```bash
./hop marketplace install io.github.michaaels:apache-hop-mcp:1.0.0 --repo apache-hop-mcp
```

Restart Hop after installation. Releases are served directly from GitHub through Hop 2.19's `urlTemplate` and `catalogUrl` support.

## Headless command

Read-only inspection:

```bash
./hop mcp --root /data/hop/project
```

Windows:

```powershell
hop.bat mcp --root C:\Hop\project
```

Enable local execution and transactional semantic mutation explicitly:

```bash
./hop mcp --root /data/hop/project --allow-execution --allow-mutation
```

Other opt-ins:

```bash
./hop mcp --root /data/hop/project --allow-deep-check
./hop mcp --root /data/hop/project --allow-web-api --web-url http://127.0.0.1:8080/hop
```

If the Projects plugin is configured, Hop's normal run-category project/environment options are loaded by the command.

## Codex configuration

```toml
[mcp_servers.apache-hop]
command = "C:\\hop\\hop.bat"
args = ["mcp", "--root", "C:\\Hop\\project", "--allow-execution", "--allow-mutation"]
```

Only include the opt-in flags that the MCP client should be authorized to use.

## Semantic authoring flow

1. Call `hop_component_types` with `kind=pipeline` or `kind=workflow` to resolve the native plugin ID.
2. Call `hop_component_schema` for that ID and use only the returned scalar property keys and tabular group keys.
3. Call `hop_mutate_definition` with `apply=false` and one or more `add_component` or `update_component` operations, followed by any required hop operations.
4. Review the preview and apply it with `apply=true`; for an existing definition, also provide its current `expected_sha256`.
5. Validate or execute the saved definition. Use the returned transaction ID and new SHA-256 if rollback is required.

For a single gated test report, call `hop_test_definition`. Structural validation always runs first. Native deep checking and execution run only when requested, authorized at server startup, and all preceding gates pass. Diagnostics and execution logs are bounded and redacted. Suggested corrections identify relevant MCP tools or semantic operations but remain advisory; the client must preview and explicitly apply any mutation.

For a correction with a reviewable lifecycle, call `hop_prepare_correction_plan` with the same bounded semantic operations accepted by `hop_mutate_definition`. The returned preview is retained only in the current MCP session and bound to a plan SHA-256 plus the definition's current SHA-256. After review, call `hop_apply_correction_plan` with both plan identifiers. Applying requires `--allow-mutation`, consumes the plan even when application fails, and delegates the write to the existing transactional mutator. Use `hop_correction_plan_status` to inspect its bounded audit trail. A changed definition, altered plan digest, expired plan, or reused plan is rejected.

`add_component` requires `plugin_id` and `name`; `properties`, `property_groups`, `x`, and `y` are optional. `properties` contains scalar values. `property_groups` maps a schema group key to an array of row objects, for example `{"fields":[{"name":"id","type":"Integer","length":9}]}`. Component identity, secret-looking fields, unknown keys, and nested collections cannot be overridden through these maps.

`update_component` requires the existing `component` name plus at least one non-empty `properties` or `property_groups` object. Scalar keys update only the requested values. Each requested tabular group replaces that complete group; groups and properties omitted from the operation remain unchanged.

## MCP tools

| Tool | Purpose |
|---|---|
| `hop_config` | server/root/security configuration |
| `hop_capabilities` | native semantic operations, guarantees, compatibility and live-UI status |
| `hop_live_ui_status` | active Desktop/Web sessions and mutation delivery acknowledgements |
| `hop_plugins` | filtered, paginated Apache Hop plugin inventory |
| `hop_component_types` | discover transforms/actions available for semantic authoring |
| `hop_component_schema` | inspect safe scalar and tabular properties accepted by a component plugin |
| `hop_catalog` | paginated project file metadata and SHA-256 fingerprints |
| `hop_list_definitions` | list `.hpl` / `.hwf` definitions |
| `hop_inspect` | components, hops, SQL tables, references |
| `hop_context` | consolidated inspection, validation and local dependencies |
| `hop_component` | inspect one transform/action with secret redaction |
| `hop_component_lineage` | upstream/downstream graph traversal |
| `hop_validate` | safe structural validation |
| `hop_deep_check` | native Hop checker; explicit opt-in |
| `hop_test_definition` | gated structural/deep/execution test with normalized diagnostics and advisory corrections |
| `hop_read_text` | bounded project file read |
| `hop_search` | bounded text search |
| `hop_find_table` | SQL table-reference discovery |
| `hop_dependencies` | referenced `.hpl` / `.hwf` dependencies |
| `hop_execute` | execute a pipeline/workflow locally and wait for its bounded result |
| `hop_start_execution` | start a bounded local execution asynchronously |
| `hop_execution_status` | read asynchronous execution state/result |
| `hop_stop_execution` | request asynchronous execution cancellation |
| `hop_logs` | read bounded, redacted Hop execution logs |
| `hop_prepare_correction_plan` | validate and retain an immutable, SHA-bound semantic mutation preview |
| `hop_apply_correction_plan` | explicitly apply one prepared single-use correction plan |
| `hop_correction_plan_status` | inspect session-scoped plan state and bounded audit events |
| `hop_mutate_definition` | preview/apply transactional native semantic changes |
| `hop_rollback_mutation` | roll back an applied mutation from this MCP session |
| `hop_web_request` | bounded GET/HEAD request to a configured Hop Web API |

## AI-assisted development

Repository agents should read [`AGENTS.md`](AGENTS.md). Detailed build instructions are in [`docs/AI_DEVELOPMENT.md`](docs/AI_DEVELOPMENT.md).

## License

Apache License 2.0. See [`LICENSE`](LICENSE).
