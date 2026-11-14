# Feeders ingestion: MCP findings and local validation

The user authorized authoring a Hop pipeline for three technology-specific
Crossed Feeder CSVs, loading tests into `bd_tablas_temporales` on MariaDB
10.32.112.109, and fixing connector deficiencies found during the exercise.
The unified georeference source is the user's updated `bd_gestion.ovw_cells`.
Business ETL files were authored through MCP, not XML string replacement.

## Native value-type metadata codes

`hop_component_schema` originally described ScriptValueMod `FIELD_TYPE` and
CSV `FIELD_TYPE` as numeric, based on their Java `int` storage. Hop 2.20 metadata
injection accepts names such as `String` and `Integer` for these annotated
properties. Numeric strings such as `2` silently produced `None`, causing a
runtime output-field conversion failure after the first CSV import committed.
That import's data was independently checked and was complete; the error was
in output metadata, not a partial database transaction.

The connector now detects the native `ValueTypeCodeConverter` annotation,
advertises string codes and allowed values, validates supplied types and
normalizes compatible numeric IDs. Hop 2.19's injection path expects numeric
IDs instead; a fallback is restricted to a NumberFormatException cause when
injecting a recognized value-type property. Arbitrary converter failures,
authorization failures and other properties do not use that fallback.

Regression coverage includes scalar and tabular injection, schema hints,
compatible numeric IDs, and rejection of unknown names, None, fractional IDs,
unsupported IDs and numeric text masquerading as a code name.

## Data grid trailing empty cells

The native DataGridDataMeta injection setter uses `String.split(regex)` and
drops trailing empty cells. Updating a catalog with eight fields produced
seven-cell rows and ArrayIndexOutOfBoundsException at runtime. The deficiency
was reproduced in a disposable project; the real catalog was left unchanged
until the correction passed.

After native injection, the connector uses the recognized Data grid's native
metadata setters to restore `split(regex, -1)` cells, validates row width
against field metadata and clears transport-only injection fields so native
reload cannot split the row again. The correction is restricted to the native
DataGridMeta class and its data property group; generic XML mutation and
nested collection injection remain unsupported.

An isolated catalog pipeline ran all 43 rows successfully. A complete cell
comparison and field-metadata comparison then confirmed that only the
GUL_CROSSED_FEEDER_DAILY manual_process cell changed in the real project.
Its pre-existing file_ext value was csv. Protected MCP backup, hash
precondition, preview and native reload were retained.

## ETL execution evidence

- New pipeline: `load_gul_crossed_feeder_daily.hpl` in the Hop project, with a
  development copy under `D:\dev\feeders\etl\hop`.
- Test table: `bd_tablas_temporales.gul_crossed_feeder_daily`.
- Source snapshot: 2026-10-21, the latest directory found during inspection.
- Source rows: GSM 4,320; UMTS 16,149; LTE 27,438; total 47,907.
- Every raw field and source SHA-256 was compared against all CSV records.
- Every LAT/LON was compared against the prototype's parser and the compiled
  inventory. Both comparisons had zero mismatches; all rows were MATCHED.
- A successful repeat load retained the same counts without duplicate rows.
- A deliberate failure before commit preserved the complete previous-table
  fingerprint and batch ID. A nonexistent source date failed during preflight.
- A final native MCP execution succeeded with an existing external credential
  source, without embedding credentials in the pipeline or source code.
- The loader's parser, coordinate conversion and JSON checks passed 13 cases.

No production-schema writes, original workflow executions or outgoing emails
were performed. Production DDL and optional prototype query configuration are
prepared separately. Only a target date is replaced, atomically across the
three files; other dates remain untouched. Missing georeferences are retained
as nulls with a status instead of being dropped or located at zero.

## Scope and evidence

Detailed plans, source manifests, fingerprints, failed reproductions and final
results are under `D:\dev\build_hop_web\mcp\feeders\20261109-123947`.
The connector remains an unpublished local 2.2.6 build. Runtime authorization,
STDIO contracts, bounds and dependency packaging are unchanged.

During local verification, one initial stable run hit a Windows temporary
directory cleanup lock, and a later stable run hit the existing asynchronous
timeout test while it read an absent result; a publication race is a possible
cause and was not investigated further. These failures were retained in the
evidence. Neither test was skipped or weakened; the final stable rerun passed.
Native Data grid execution was verified on installed Hop 2.20.0; the stable
Hop 2.19 build additionally checks compatibility and the pure regression cases.
