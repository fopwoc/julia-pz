"""Compare completed recordings using common frame metrics, with no renderer-specific counters."""
from __future__ import annotations

import statistics
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


def summarize(scenario: str, results: list[dict]) -> str:
    """Medians over repetitions per spot; the range shows whether a difference is larger than run-to-run noise."""
    lines = ["# " + scenario + " summary", "", "| Spot | Renderer | Runs | Average FPS (median) | FPS range | p99 frame time (ms, median) |",
             "| --- | --- | ---: | ---: | ---: | ---: |"]
    for spot in dict.fromkeys(item["spot"] for item in results):
        for renderer in ("opengl", "julia"):
            runs = [properties(Path(item["capture"]) / "summary.properties") for item in results
                    if item["renderer"] == renderer and item["spot"] == spot]
            if not runs:
                continue
            fps = [float(run["average_fps"]) for run in runs]
            p99 = [int(run["p99_ns"]) / 1e6 for run in runs]
            lines.append(f"| {spot} | {renderer} | {len(runs)} | {statistics.median(fps):.2f} | {min(fps):.2f}-{max(fps):.2f} | {statistics.median(p99):.3f} |")
    return "\n".join(lines) + "\n"
