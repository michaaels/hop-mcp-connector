# Readable pipeline and workflow canvases

Use `auto_layout` as the final operation when authoring a pipeline or workflow with `hop_mutate_definition`. It changes native component coordinates only. Existing components, hops, enabled flags, conditions, parameters, scripts, metadata references and execution settings remain unchanged. No dummy transforms or actions are introduced to bend connections.

Preview an existing definition:

```json
{
  "path": "example.hpl",
  "operations": [{ "operation": "auto_layout" }],
  "apply": false
}
```

Review the preview's `changes` and then apply the same request with `apply: true` and `expected_sha256` equal to the file's current SHA-256. The ordinary protected backup, native reload, stale-write check, same-session rollback and live-UI dirty-tab protection apply. The tool remains gated by `--allow-mutation`; the operation does not enable execution or change authorization.

`x` and `y` optionally set the canvas origin (default `80,80`). These are the only additional accepted operation fields. Layout supports at most 200 components and 1,000 total hops per definition; exceeding either limit rejects the operation without writing. No external layout service or new runtime dependency is used.

## Layout strategy

- Rank enabled connections from left to right; align simple chains and reserve space for labels.
- Separate workflow false branches and native pipeline error hops from the normal path where they remain separate. Shared recovery/merge targets stay in the normal flow; Abort components stay below it.
- Apply four bounded branch-order sweeps and bounded local improvements to reduce straight-line crossings and bypasses through component centers.
- Recognize cycles for placement, while preserving their original return connections.
- Include disconnected components without overlapping their icons. Ignore disabled connections when calculating ranks and metrics, while preserving those connections in the file.

Notes retain their position and contents. Review note placement and exceptionally long labels in Hop GUI after applying. Hops use Hop's own renderer; this operation does not add bend points, force orthogonal lines, or promise a crossing-free drawing for every graph. Large or dense flows can still benefit from a business-level split into reusable pipelines/workflows, which requires a separate semantic decision.

## Preview measurements

Each layout change returns `component_count`, `moved_components`, `direction`, `notes_moved` and `before`/`after` measurements. `review_required` is true if the proposed geometry still contains measured crossings, overlaps, segments through other component centers or backward connections. A legitimate cycle can therefore require review without being structurally invalid.

| Measurement | Meaning |
| --- | --- |
| `crossings` | Proper intersections of enabled straight connections without shared endpoints |
| `component_overlaps` | Component anchor separation below a 64 × 80 pixel envelope |
| `edges_through_components` | Enabled segments passing exactly through another component's center |
| `backward_hops` | Enabled connections that do not advance horizontally |

These are geometric estimates, not pixel measurements from Hop GUI. Shared/collinear connections, arbitrary text extents and note collisions are not included in the crossing count. A heuristic improvement is not a guarantee of the global minimum.

Validation covers native preview/apply/rollback, stale SHA rejection, parameter/configuration preservation, branch separation, crossing reduction, straight bypasses, cycles, disconnected nodes, empty flows, size bounds, deterministic repetition and the existing STDIO input/output schemas. Do not execute ETL merely to validate a canvas-only change.
