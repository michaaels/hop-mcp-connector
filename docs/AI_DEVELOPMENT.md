# AI development guide

## Codex CLI / local

Prerequisites: Java 21 and Apache Maven 3.9+.

```bash
rtk java -version
rtk mvn -version
rtk mvn -B clean verify
```

For an integration smoke test, install the generated ZIP into a disposable Apache Hop installation, restart Hop, and run:

```bash
rtk ./hop mcp --help
```

Then configure an MCP client to launch `hop mcp --root <fixture-project>` over STDIO.

## Codex Cloud

The default Cloud task should build/test source without requiring a locally installed Hop client because released Hop artifacts are resolved by Maven. Do not connect Cloud jobs to production Hop Server instances, databases, secret stores, or production projects.

Expected setup:

```bash
rtk mvn -B -DskipTests dependency:go-offline
rtk mvn -B clean verify
```

If network policy prevents Maven resolution, report that limitation rather than replacing Hop APIs with guessed stubs in production source.

## API discipline

Use Apache Hop `release/2.19.0` as the compatibility source. In particular:

- `@HopCommand` + `IHopCommand` provide the headless command.
- `Hop` discovers command plugins from `PluginRegistry`.
- `@GuiPlugin` + `@GuiMenuElement` provide the GUI integration.
- `PipelineMeta` / `WorkflowMeta` native checks may reach configured systems and are never a safe static validator.

## STDIO discipline

MCP JSON-RPC owns stdout. Capture the original stdout for the MCP transport and redirect normal application output to stderr before starting the server. A regression that emits arbitrary text on stdout can break every MCP client.

## Marketplace packaging

`mvn package` must produce a ZIP rooted at the Hop installation layout:

```text
plugins/misc/hop-mcp-connector/
```

Runtime third-party MCP libraries go into the plugin `lib/` folder. Hop libraries remain `provided`.

## Security review checklist

Before completing a security-sensitive change verify:

- Can any path escape project root after symlink resolution?
- Can an XML external entity read local/network data?
- Can a secret appear in `hop_component` or logs?
- Can a nominally read-only tool execute a pipeline/workflow or invoke arbitrary shell code?
- Can a deep checker contact an external service without explicit opt-in?
- Can unbounded input consume excessive memory/CPU?
- Can non-protocol output reach stdout?
