# Full-Hop performance regression checks

The production connector remains native Java, controlled through MCP STDIO. These Python/Node scripts are development-only clients; they are not shipped in the Marketplace plugin.

Use an isolated Windows Apache Hop 2.20.0 distribution **without an installed MCP connector**, Java 21, Node.js, Python 3, and the verified 5,000-definition fixture. The runner installs only the connector directory in that isolated runtime, replaces its own installation between trials, and removes it on exit. It rejects existing connector installations and existing output directories. Never point it at a working Hop installation.

```powershell
rtk proxy python scripts/ci/full-hop-benchmark.py --runtime <isolated-hop> --fixture <definitions-5000> --java-home <jdk-21> --baseline <published-stable.zip> --candidate <development.zip> --output outputs/full-hop-run
```

Add `--generate-fixture` with a **new** fixture directory to reproduce the fixed fixture locally. Existing fixture directories are never overwritten. Generation is outside the timed Hop processes and the same SHA-256 check still applies.

The default is five fresh processes per version, alternating order, with five hot queries per process. Each run checks Java 21, Hop 2.20 JAR manifest versions (not just filenames), ZIP/server versions, TableInput and FilterRows plugin availability, structural validation, 64 unique impact nodes, 63 edges, complete counts, and no truncation. Canonical results must match both within a process and across versions/processes. The fixture SHA-256 is fixed to the previously verified baseline; a different fixture requires a separately reviewed baseline, not bypassing the check.

The manual [Windows workflow](../.github/workflows/full-hop-benchmark.yml) selects the latest compatible published stable ancestor, or accepts a `baseline_tag` override. `raw/` retains each process result, `results.json` keeps validated timings, result hashes, plugin checks and index metrics, and `environment.json` records OS, CPU, Java, Node, heap, commit and artifact hashes without dumping environment variables. `report.md` reports medians, ranges and median absolute deviation; `error.json` captures partial failures. Output is never overwritten and should remain outside `target/` so `mvn clean` cannot erase it. Timing changes are informational, not evidence of correctness on their own. This fixture covers two real transform plugins, not every third-party Hop plugin or database system.

For optional JFR diagnostics, `full-hop-measure.mjs` accepts an additional recording-directory argument after the trial number. Do not run concurrent Hop processes when using its diagnostic JVM selection.
