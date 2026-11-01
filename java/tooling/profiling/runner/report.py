"""Compare completed recordings using common frame metrics, with no renderer-specific counters."""
from __future__ import annotations

from pathlib import Path


def properties(path: Path) -> dict[str, str]:
    return dict(line.split("=", 1) for line in path.read_text().splitlines() if line and not line.startswith("#") and "=" in line)


def compare(baseline: Path, candidate: Path) -> str:
    first, second = properties(baseline / "summary.properties"), properties(candidate / "summary.properties")
    for result in (first, second):
        if result["status"] != "COMPLETE" or int(result["frames"]) == 0:
            raise ValueError("Compare only complete nonempty recordings")
    if any(first[key] != second[key] for key in ("scenario", "warmup_ms", "duration_ms")):
        raise ValueError("Scenario and capture timing must match")
    lines = ["# " + first["scenario"], "", "| Metric | Baseline | Candidate |", "| --- | ---: | ---: |"]
    lines.append(f"| Average FPS | {float(first['average_fps']):.2f} | {float(second['average_fps']):.2f} |")
    for label, key in (("Median", "median_ns"), ("p95", "p95_ns"), ("p99", "p99_ns"), ("Maximum", "max_ns")):
        lines.append(f"| {label} frame time (ms) | {int(first[key])/1e6:.3f} | {int(second[key])/1e6:.3f} |")
    return "\n".join(lines) + "\n\nCPU wall timings only. Compare identical saves, views, resolution, settings, debug mode, and FPS limits.\n"
