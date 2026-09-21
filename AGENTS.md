# AGENTS.md

This file is the operational contract for AI coding agents working in this repository.

## Mission

Maintain a small, secure, native-Java MCP plugin for Apache Hop. The Marketplace edition must remain installable as one Hop plugin and expose `hop mcp` through Hop's `@HopCommand` plugin discovery.

## Supported baseline

- Java: 21
- Apache Hop: 2.19.x (`hop.version=2.19.0` compile baseline)
- MCP Java SDK: 2.0.1
- Transport: STDIO
- Maven coordinate: `io.github.michaaels:apache-hop-mcp`

## Architecture

```text
MCP client
  -> hop mcp
  -> HopMcpCommand
  -> HopMcpServer
  -> HopMcpService
  -> safe static project inspection / selected native Hop APIs
  -> Apache Hop runtime
```

Do not reintroduce Python or a Java subprocess bridge into the Marketplace runtime.

## Non-negotiable security rules

1. Never add execution, mutation, or broader authorization in a patch release.
2. Never allow project paths to escape the configured root through `..`, absolute-path tricks, or symlinks.
3. Keep DTDs and XML external entities disabled.
4. Never print secrets returned from Hop metadata or XML. Extend redaction when new sensitive keys are found.
5. STDIO stdout is protocol-only. Application/Hop logging belongs on stderr.
6. Deep validation that can resolve fields or access external systems must remain explicit opt-in.
7. Do not bundle `hop-core`, `hop-engine`, or `hop-ui` in the Marketplace ZIP.
8. Do not weaken file-size, scan-count, result-count, or traversal-depth bounds without a documented reason and tests.

## Before editing

Read the relevant source and Apache Hop 2.19 API before guessing a method signature. Prefer release/2.19.0 source when compatibility matters.

## Required validation

For ordinary changes run:

```bash
mvn -B clean verify
```

For packaging changes additionally inspect the ZIP:

```bash
unzip -l target/apache-hop-mcp-1.0.0.zip
```

It must contain `plugins/misc/apache-hop-mcp/` and must not contain Apache Hop runtime jars.

## Release rules

- Plugin version and Marketplace catalog version must agree.
- Release ZIP name is exactly `apache-hop-mcp-${version}.zip`.
- GitHub Release tag is `v${version}`.
- Keep Marketplace `minHopVersion` aligned with the tested baseline.

## Semantic mutation rules

Native mutation must use Hop semantic objects, not arbitrary XML string replacement. Python/outer transaction logic from older prototypes is not a requirement. A mutation implementation must provide: expected SHA-256, preview, backup, atomic write, native reload validation, and rollback.
