#!/usr/bin/env python3
"""Explicit profiling launch/control commands. 'plan' never launches the game or writes a profile."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from runner.control import request
from runner.launch import plan
from runner.report import compare
from runner.benchmark import Benchmark
from runner.scenario import Scenario


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    root = Path(__file__).resolve().parents[2]
    bundled_observer = Path(__file__).parent / "lib/profiling-agent.jar"
    default_observer = bundled_observer if bundled_observer.is_file() else Path(__file__).parent / "agent/build/libs/profiling-agent.jar"
    for command in ("plan", "launch", "benchmark-plan", "benchmark"):
        item = commands.add_parser(command)
        item.add_argument("--game", type=Path, required=True)
        item.add_argument("--run", type=Path, required=True)
        if command in ("plan", "launch"):
            item.add_argument("--renderer", choices=("opengl", "julia"), required=True)
        else:
            item.add_argument("--scenario", type=Path, required=True)
            item.add_argument("--repetitions", type=int, default=3)
            item.add_argument("--startup-timeout", type=float, default=180)
        item.add_argument("--observer", type=Path, default=default_observer)
        item.add_argument("--julia", type=Path, default=root / "modules/agent/build/libs/julia-agent.jar")
        item.add_argument("--buddy", type=Path)
        item.add_argument("--java", type=Path)
        item.add_argument("--profile-template", type=Path)
        item.add_argument("--debug", action="store_true")
    record = commands.add_parser("record")
    record.add_argument("run", type=Path)
    record.add_argument("--scenario", required=True)
    record.add_argument("--warmup", type=float, default=20)
    record.add_argument("--duration", type=float, default=60)
    stop = commands.add_parser("stop")
    stop.add_argument("run", type=Path)
    report = commands.add_parser("compare")
    report.add_argument("baseline", type=Path)
    report.add_argument("candidate", type=Path)
    args = parser.parse_args()
    if args.command in ("plan", "launch"):
        selected = plan(args.game, args.run, args.observer, args.renderer, args.julia, args.buddy,
                        args.java, args.profile_template, args.debug)
        if args.command == "plan": print(json.dumps(selected.describe(), indent=2))
        else: raise SystemExit(selected.launch())
    elif args.command in ("benchmark-plan", "benchmark"):
        import math
        if not 1 <= args.repetitions <= 100 or not math.isfinite(args.startup_timeout) or args.startup_timeout <= 0:
            parser.error("Use 1..100 repetitions and a positive finite startup timeout")
        if args.run.exists(): parser.error("Choose a fresh benchmark directory")
        scenario = Scenario.read(args.scenario)
        scenario.validate_template(args.profile_template)
        runs = []
        for repeat in range(args.repetitions):
            order = ("opengl", "julia") if repeat % 2 == 0 else ("julia", "opengl")
            for renderer in order:
                runs.append(plan(args.game, args.run / f"{repeat + 1:02}-{renderer}", args.observer, renderer,
                                 args.julia, args.buddy, args.java, args.profile_template, args.debug))
        selected = Benchmark(args.run.resolve(), scenario, tuple(runs), args.startup_timeout)
        if args.command == "benchmark-plan": print(json.dumps(selected.describe(), indent=2))
        else: selected.launch()
    elif args.command == "record": print(request(args.run, args.scenario, args.warmup, args.duration))
    elif args.command == "stop": print(request(args.run))
    else: print(compare(args.baseline, args.candidate))


if __name__ == "__main__": main()
