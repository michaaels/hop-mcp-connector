#!/usr/bin/env python3
"""Validate benchmark correctness and produce comparison JSON, CSV, and Markdown."""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
from typing import Any


EXPECTED_ROWS = {
    (f"definitions-{size}", phase)
    for size in (1_000, 2_500, 5_000)
    for phase in ("cold", "warm_median_x5", "one_file_changed")
} | {
    ("mixed-20k-files-5k-definitions", "cold_bounds"),
    ("available-definitions-10000", "cold_bounds"),
    ("available-definitions-20000", "cold_bounds"),
}


def load_run(path: Path, expected_mode: str) -> dict[str, Any]:
    run = json.loads(path.read_text(encoding="utf-8"))
    if run.get("mode") != expected_mode:
        raise ValueError(f"{path}: expected mode {expected_mode!r}")
    rows = run.get("rows")
    if not isinstance(rows, list):
        raise ValueError(f"{path}: rows must be a JSON array")
    keyed = {(row.get("scenario"), row.get("phase")): row for row in rows}
    if len(keyed) != len(rows) or set(keyed) != EXPECTED_ROWS:
        raise ValueError(f"{path}: benchmark scenario set is incomplete or duplicated")
    run["rows_by_key"] = keyed
    return run


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def validate_run(run: dict[str, Any], mode: str) -> None:
    rows = run["rows_by_key"]
    current = mode == "current"
    for size in (1_000, 2_500, 5_000):
        for phase in ("cold", "warm_median_x5", "one_file_changed"):
            row = rows[(f"definitions-{size}", phase)]
            require(row["definitions"] == size, f"{mode} {size}/{phase}: wrong definition count")
            require(row["truncated"] is False, f"{mode} {size}/{phase}: unexpected truncation")
            require(row["files_available"] == size, f"{mode} {size}/{phase}: wrong fixture size")
            if current:
                require(
                    row["files_examined"] == size,
                    f"{mode} {size}/{phase}: wrong regular-file examination count",
                )
                expected_refreshes = {
                    "cold": 1,
                    "warm_median_x5": 5,
                    "one_file_changed": 1,
                }[phase]
                require(
                    row["refreshes"] == expected_refreshes,
                    f"{mode} {size}/{phase}: unexpected refresh counter",
                )
            if phase == "cold":
                require(row["cache_hits"] == 0, f"{mode} {size}: cold scan has cache hits")
                require(row["cache_misses"] == size, f"{mode} {size}: cold scan missed definitions")
            elif phase == "warm_median_x5":
                require(row["cache_hits"] == size, f"{mode} {size}: warm scan did not reuse all entries")
                require(row["cache_misses"] == 0, f"{mode} {size}: warm scan reparsed entries")
            else:
                require(row["cache_hits"] == size - 1, f"{mode} {size}: changed scan hit count mismatch")
                require(row["cache_misses"] == 1, f"{mode} {size}: expected exactly one reparse")
            require(
                row["node_count"] == 64,
                f"{mode} {size}/{phase}: impact graph node count differs from the 64-node fixture",
            )

    mixed = rows[("mixed-20k-files-5k-definitions", "cold_bounds")]
    require(mixed["files_available"] == 20_000, f"{mode}: wrong mixed fixture size")
    if current:
        require(mixed["definitions"] == 5_000, "current mixed scan omitted definitions")
        require(mixed["files_examined"] == 20_000, "current mixed scan did not examine all files")
        require(mixed["truncated"] is False, "current mixed scan truncated below project bounds")
    else:
        require(mixed["definitions"] <= 5_000, "baseline exceeded its historical scan bound")
        require(mixed["files_examined"] <= 5_000, "baseline exceeded its historical scan bound")
        require(mixed["truncated"] is True, "baseline failed to report its historical scan bound")

    for size in (10_000, 20_000):
        row = rows[(f"available-definitions-{size}", "cold_bounds")]
        require(row["files_available"] == size, f"{mode} {size}: wrong fixture size")
        require(row["definitions"] == 5_000, f"{mode} {size}: definition page limit mismatch")
        require(row["truncated"] is True, f"{mode} {size}: missing truncation signal")
        require(row["files_examined"] <= size, f"{mode} {size}: examined more files than available")
        if current:
            require(row["files_examined"] == size, f"{mode} {size}: failed to examine all available files")


def number(value: Any) -> str:
    return "" if value is None else str(value)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--current", type=Path, required=True)
    parser.add_argument("--baseline-ref", required=True)
    parser.add_argument("--current-ref", required=True)
    parser.add_argument("--json", dest="json_path", type=Path, required=True)
    parser.add_argument("--csv", dest="csv_path", type=Path, required=True)
    parser.add_argument("--summary", type=Path)
    args = parser.parse_args()

    baseline = load_run(args.baseline, "baseline")
    current = load_run(args.current, "current")
    validate_run(baseline, "baseline")
    validate_run(current, "current")

    results = []
    for key in sorted(EXPECTED_ROWS):
        old = baseline["rows_by_key"][key]
        new = current["rows_by_key"][key]
        old_duration = old["duration_ms"]
        new_duration = new["duration_ms"]
        results.append(
            {
                "scenario": key[0],
                "phase": key[1],
                "baseline": old,
                "current": new,
                "current_over_baseline_ratio": (
                    new_duration / old_duration if old_duration else None
                ),
                "current_minus_baseline_ms": new_duration - old_duration,
            }
        )

    report = {
        "baseline": {
            "ref": args.baseline_ref,
            "java_version": baseline["java_version"],
            "available_processors": baseline["available_processors"],
            "max_heap_bytes": baseline["max_heap_bytes"],
        },
        "current": {
            "ref": args.current_ref,
            "java_version": current["java_version"],
            "available_processors": current["available_processors"],
            "max_heap_bytes": current["max_heap_bytes"],
        },
        "results": results,
        "latency_is_informational": True,
    }
    args.json_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    csv_fields = [
        "scenario",
        "phase",
        "baseline_duration_ms",
        "current_duration_ms",
        "current_over_baseline_ratio",
        "current_minus_baseline_ms",
        "baseline_definitions",
        "current_definitions",
        "baseline_files_examined",
        "current_files_examined",
        "baseline_cache_hits",
        "current_cache_hits",
        "baseline_cache_misses",
        "current_cache_misses",
        "baseline_refreshes",
        "current_refreshes",
        "baseline_truncated",
        "current_truncated",
        "baseline_node_count",
        "current_node_count",
    ]
    with args.csv_path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=csv_fields)
        writer.writeheader()
        for result in results:
            old = result["baseline"]
            new = result["current"]
            writer.writerow(
                {
                    "scenario": result["scenario"],
                    "phase": result["phase"],
                    "baseline_duration_ms": old["duration_ms"],
                    "current_duration_ms": new["duration_ms"],
                    "current_over_baseline_ratio": result["current_over_baseline_ratio"],
                    "current_minus_baseline_ms": result["current_minus_baseline_ms"],
                    **{f"baseline_{key}": number(old.get(key)) for key in (
                        "definitions", "files_examined", "cache_hits", "cache_misses",
                        "refreshes", "truncated", "node_count"
                    )},
                    **{f"current_{key}": number(new.get(key)) for key in (
                        "definitions", "files_examined", "cache_hits", "cache_misses",
                        "refreshes", "truncated", "node_count"
                    )},
                }
            )

    lines = [
        "# Project definition index benchmark",
        "",
        f"Baseline: `{args.baseline_ref}`; current: `{args.current_ref}`.",
        "Same runner, same Java installation, and shared fixture files.",
        "Latency is informational; the job fails only when fixture or index correctness checks fail.",
        "",
        "| Scenario | Phase | Baseline ms | Current ms | Current / baseline | Delta ms | Definitions | Files examined | Cache hits | Cache misses | Refreshes | Truncated | Node count |",
        "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---:|",
    ]
    for result in results:
        old = result["baseline"]
        new = result["current"]
        ratio = result["current_over_baseline_ratio"]
        lines.append(
            "| {scenario} | {phase} | {old_ms:.3f} | {new_ms:.3f} | {ratio} | {delta:+.3f} | "
            "{old_defs} -> {new_defs} | {old_files} -> {new_files} | {old_hits} -> {new_hits} | "
            "{old_misses} -> {new_misses} | {old_refreshes} -> {new_refreshes} | {old_truncated} -> {new_truncated} | {node_count} |".format(
                scenario=result["scenario"],
                phase=result["phase"],
                old_ms=old["duration_ms"],
                new_ms=new["duration_ms"],
                ratio="n/a" if ratio is None else f"{ratio:.2f}x",
                delta=result["current_minus_baseline_ms"],
                old_defs=old["definitions"],
                new_defs=new["definitions"],
                old_files=old["files_examined"],
                new_files=new["files_examined"],
                old_hits=old["cache_hits"],
                new_hits=new["cache_hits"],
                old_misses=old["cache_misses"],
                new_misses=new["cache_misses"],
                old_refreshes=number(old.get("refreshes")) or "n/a",
                new_refreshes=number(new.get("refreshes")) or "n/a",
                old_truncated=old["truncated"],
                new_truncated=new["truncated"],
                node_count=number(new.get("node_count")) or "n/a",
            )
        )
    summary = "\n".join(lines) + "\n"
    if args.summary:
        args.summary.write_text(summary, encoding="utf-8")
    else:
        print(summary)


if __name__ == "__main__":
    main()
