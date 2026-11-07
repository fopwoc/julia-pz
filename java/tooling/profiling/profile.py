#!/usr/bin/env python3
"""Profiling commands. 'run' does the whole comparison; 'run --dry-run' and 'plan' never launch or write anything."""
from __future__ import annotations

import argparse
import json
import platform
import subprocess
from pathlib import Path
from runner.bake import DEFAULT_TEMPLATE, bake
from runner.benchmark import Benchmark
from runner.control import request
from runner.launch import plan
from runner.report import compare
from runner.scenario import Scenario


def installed_game() -> Path:
    steam = "Library/Application Support/Steam" if platform.system() == "Darwin" else ".steam/steam"
    game = Path.home() / steam / "steamapps/common/ProjectZomboid"
    if not game.is_dir():
        raise SystemExit("Game not found at " + str(game) + "; pass --game")
    return game


def session_running() -> bool:
    # The game's own launcher or main class, so the paths in this command's arguments cannot match.
    return subprocess.run(["pgrep", "-f", "Project Zomboid.app/Contents/MacOS|ProjectZomboid64|zombie.gameStates.MainScreenState"],
                          capture_output=True).returncode == 0


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    tool = Path(__file__).parent
    bundled_observer = tool / "lib/profiling-agent.jar"
    default_observer = bundled_observer if bundled_observer.is_file() else tool / "agent/build/libs/profiling-agent.jar"
    for command in ("plan", "launch", "run"):
        item = commands.add_parser(command)
        item.add_argument("--game", type=Path)
        item.add_argument("--observer", type=Path, default=default_observer)
        item.add_argument("--buddy", type=Path)
        item.add_argument("--java", type=Path)
        item.add_argument("--debug", action="store_true")
        if command == "run":
            item.add_argument("scene", type=Path)
            item.add_argument("--out", type=Path)
            item.add_argument("--repetitions", type=int)
            item.add_argument("--startup-timeout", type=float, default=300)
            item.add_argument("--profile-template", type=Path, default=DEFAULT_TEMPLATE)
            item.add_argument("--dry-run", action="store_true")
        else:
            item.add_argument("--run", type=Path, required=True)
            item.add_argument("--renderer", choices=("opengl", "julia"), required=True)
            item.add_argument("--profile-template", type=Path)
    baking = commands.add_parser("bake")
    baking.add_argument("save", help="Mode/SaveName under the source profile's Saves folder")
    baking.add_argument("--source", type=Path, default=Path.home() / "Zomboid")
    baking.add_argument("--out", type=Path, default=DEFAULT_TEMPLATE)
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
        selected = plan(args.game or installed_game(), args.run, args.observer, args.renderer, args.buddy,
                        args.java, args.profile_template, args.debug)
        if args.command == "plan": print(json.dumps(selected.describe(), indent=2))
        else: raise SystemExit(selected.launch())
    elif args.command == "run":
        scenario = Scenario.read(args.scene)
        repetitions = args.repetitions or scenario.repetitions
        if not 1 <= repetitions <= 100 or args.startup_timeout <= 0:
            parser.error("Use 1..100 repetitions and a positive startup timeout")
        root = args.out or tool / "build/runs" / (scenario.name + "-" + _stamp())
        if root.exists(): parser.error("Choose a fresh output directory")
        scenario.validate_template(args.profile_template)
        game = args.game or installed_game()
        runs = []
        for repeat in range(repetitions):
            # Alternating the order keeps warm OS caches from always favoring whichever renderer runs second.
            for renderer in ("opengl", "julia") if repeat % 2 == 0 else ("julia", "opengl"):
                runs.append(plan(game, root / f"{repeat + 1:02}-{renderer}", args.observer, renderer, args.buddy,
                                 args.java, args.profile_template, args.debug))
        selected = Benchmark(root.resolve(), scenario, tuple(runs), args.startup_timeout)
        if args.dry_run:
            print(json.dumps(selected.describe(), indent=2))
            return
        if session_running(): parser.error("Close the game first: a batch needs the machine to itself")
        selected.launch()
        print((root / "summary.md").read_text())
        print("Results in " + str(root))
    elif args.command == "bake":
        print("Baked " + str(bake(args.source, args.save, args.out)))
    elif args.command == "record": print(request(args.run, args.scenario, args.warmup, args.duration))
    elif args.command == "stop": print(request(args.run))
    else: print(compare(args.baseline, args.candidate))


def _stamp() -> str:
    import datetime
    return datetime.datetime.now().strftime("%Y%m%d-%H%M%S")


if __name__ == "__main__":
    try:
        main()
    except ValueError as invalid:
        # Bad scenes, templates and game folders are user input, not tool failures.
        raise SystemExit("error: " + str(invalid))
