# Local validation — 2026-11-09

Source directory: `D:\dev\hop-mcp-connector-main`. This extracted directory
has no Git metadata; the pre-change source was copied to
`D:\dev\build_hop_web\mcp\development\20261109-100432\baseline` before editing.
The changes remain unreleased under the existing 2.2.6 development version.

## Results

| Check | Result |
| --- | --- |
| Java 21 / Hop 2.19 baseline, clean verify | 166 tests; 0 failures/errors; 10 skipped; formatting passed. |
| Java 21 / Hop 2.20.0 final, clean verify | 166 tests; 0 failures/errors; 3 skipped; formatting passed. |
| New regression coverage | Eight production-regression tests and one cached-console/invalid-request test. |
| Real Hop 2.20.0 SDK/STDIO smoke | Passed; 24 default tools; EOF exit 0; no ETL execution tools enabled. |
| Serialized tools/list | 92,559 bytes; previous observed response was 35,079,556 bytes. |
| Invalid required arguments, then valid call | Rejected cleanly; session stayed usable; stdout remained JSON-RPC. |
| Native folder variable / notes / unresolved variable | Correct child resolution; notes excluded; unknown existence reported as null. |
| Missing routing target | Structural validation rejected the synthetic missing destination. |
| Marketplace ZIP | LICENSE, NOTICE, Jandex and matching main JAR checked; no Hop runtime/test JARs bundled. |
| SBOM | CycloneDX JSON and XML generated and validated in the final compatibility build. |

The stable offline build intentionally skipped the online-only SBOM goal;
the subsequent final-Hop compatibility verification ran that goal successfully.
Tests and the native smoke used disposable fixtures. At this initial verification
stage, production ETL files and the installed plugin in `C:\hop` were unchanged.

## Artifacts

- Corrected local ZIP: `target/hop-mcp-connector-2.2.6.zip`.
- ZIP SHA-256: `94D76DD5BA34CC01159C8A501DA332841D6394BD75943F5BABAFA27A97F099FC`.
- Main JAR SHA-256: `9DA75BC405AF156EE8279ABAEA883E2C27231C59E99EDBB4BDF95C17286069EC`.
- Full evidence, source backup and reports:
  `D:\dev\build_hop_web\mcp\development\20261109-100432`.

The source-change inventory and verification.json are stored with that evidence.
The Maven repository is `D:\dev\build_hop_web\cache\maven`; temporary files
and the disposable client are under the evidence directory. No APPDATA
download directory was used.

This is local verification, not a claim of published release, hosted CI,
live VS Code UI validation or production ETL equivalence.

## Follow-up installation for Codex

On 2026-11-09 at 10:51 (America/Guayaquil), the configured Codex transport
still launched the old connector from `C:\hop`. Its main JAR SHA-256 was
`CB8A13FD7FBA587FD8EB4616817864F6F48B735252A27C041405744A3EAC8713`.
The corrected main JAR was installed there after backing up the entire plugin
to `D:\dev\build_hop_web\mcp\deployment\20261109-105054\backup\hop-mcp-connector`.
The other 15 package files matched the installed files and were left unchanged.
The installed main JAR now matches the corrected SHA-256 recorded above.

Using the transport exported by `codex mcp get apache_hop --json`, the installed
client completed initialization, advertised 24 tools in 92,559 bytes, answered
`hop_config` and `hop_list_definitions`, and exited with code 0 on EOF.
This is a 99.74% reduction from the previous 35,079,556-byte catalog, below the
observed Codex 16,777,216-byte line limit. The problem was the serialized schema
size, rather than the ZIP size on disk.

Deployment hashes, backup location and connection results are recorded in
`D:\dev\build_hop_web\mcp\deployment\20261109-105054\installation.json`
and `connection-validation.json` in that same directory. The Codex configuration
and ETL files were not edited. The running Hop GUI was left open. An existing
Codex session must reconnect to load the new catalog; its live VS Code UI has
not been verified by this check. All staging and temporary files stayed under
`D:\dev\build_hop_web`; no APPDATA downloads were needed.
