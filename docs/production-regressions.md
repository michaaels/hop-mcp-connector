# Local production-regression fixes

Measured build, test and native STDIO results are recorded in
[local validation](local-validation-2026-11-09.md).

These unreleased source changes address the findings collected with Hop
2.20.0 final, Java 21 and MCP Connector 2.2.6. They do not add tools or enable
mutation, execution, deep-check or web access by default.

## Catalog and transport

The previous component output schema repeated both recursive alternatives
at every depth. Its outputSchema occupied about 35 MB and tools/list exceeded
Codex's observed 16 MiB line limit. Finite, depth-indexed `$defs` now share
the repeated schemas. The maximum depth remains 16, property count 128,
array length 100 and text length 1024. STDIO catalog tests enforce a 1 MiB
serialized-response budget, including error-aware output schemas.

Hop can initialize Log4j console managers before the command redirects
System.out. The CLI now rebinds existing SYSTEM_OUT console appenders to
stderr as well. This covers SDK validation warnings emitted after an invalid
request. The installed-client smoke sends invalid arguments followed by a
valid call and rejects any non-JSON-RPC stdout line.

## Definition references

Inspection, dependency lookup, definition comparison and impact analysis now
use declared definition-file fields. A historical filename in a description,
notepad, script or SQL literal is not a dependency. Declared paths containing
spaces are preserved instead of being split by a text regex.

Resolution uses a separate variable scope for the inspected file. It sets
PROJECT_HOME, Internal.Entry.Current.Folder, the canonical
Internal.Pipeline.Filename.Directory and Internal.Workflow.Filename.Folder,
plus their filename variables. These file-local internal values override
stale inherited values; other inherited user variables remain available.
Resolved local file URIs are supported. Paths must remain inside the project
after normalization and real-path checks; `.hop-mcp` remains private.

Dependency rows retain reference/resolved/error and add resolution_status:

| resolution_status | exists | Meaning |
| --- | --- | --- |
| resolved | true | The confined resolved path exists. |
| missing | false | Resolution succeeded but the file is absent. |
| unresolved | null | A variable has no value in this file's context. |
| rejected | null | The path could not be checked safely or was invalid. |

The invalid imported spelling Internal.Pipeline.Filename.Folder is not
silently treated as a native alias. Structural validation warns about it.

## Routing and validation

Workflow hops expose evaluation, unconditional and branch labels. Pipeline
hops identify true/false, case/default and executor-result branches where
declared; switch case values are not added to the edge summary. Component
lineage preserves these attributes.

hop_validate checks configured FilterRows/SwitchCase/executor destinations
against component names and enabled outgoing hops. Missing targets are
errors; unconnected destinations and legacy *_target_step properties are
diagnostics to review. The latter need to be checked against the native
runtime's property spelling. Errors and warnings keep the existing bounds.

The response explicitly includes validation_level=structural and
execution_validated=false. These checks parse local metadata only: they do
not resolve field streams, open JDBC connections, execute ETL, publish to
GeoServer or test SMTP. The existing native/deep-check authorization gates
are unchanged.

## Verification

Use Java 21 and a workspace Maven repository/temp folder. For example:

```powershell
$env:JAVA_HOME = 'D:\dev\build_hop_web\tools\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn -B -Dmaven.repo.local=D:\dev\build_hop_web\cache\maven clean verify
```

The ordinary build now targets final Hop 2.20.0 exclusively. Native component
and JDBC tests run without a profile.
`scripts/ci/hop-smoke.mjs` also exercises the real installed SDK/STDIO path
against a disposable project and client. Never point this fixture-writing
CI helper at a production project.
