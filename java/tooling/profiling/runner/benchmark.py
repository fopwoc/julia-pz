"""Explicit repeated game runs with bounded handshakes and fresh profile copies."""
from __future__ import annotations

import json
import os
import subprocess
import tempfile
import time
from dataclasses import asdict, dataclass
from pathlib import Path

from .control import request
from .launch import LaunchPlan
from .report import compare, properties
from .scenario import Scenario


def read_status(path: Path) -> str:
    try:
        return path.read_text().strip()
    except FileNotFoundError:
        return ""


def quit_scenario(run: Path) -> None:
    folder = run / "profile/Lua"
    fd, name = tempfile.mkstemp(dir=folder, prefix="profile-command.")
    try:
        with os.fdopen(fd, "w") as stream:
            stream.write("quit\n")
        os.replace(name, folder / "profile-command.txt")
    finally:
        if os.path.exists(name): os.unlink(name)


def wait_until(process, run: Path, predicate, timeout: float) -> None:
    deadline = time.monotonic() + timeout
    while True:
        for path in (run / "status.txt", run / "profile/Lua/profile-scene.txt", run / "loading-hook.txt"):
            status = read_status(path)
            if status.startswith("ERROR"):
                raise RuntimeError(str(path) + ": " + status)
        if predicate(): return
        if process.poll() is not None:
            raise RuntimeError("Game exited before the scenario completed; see " + str(run / "game-output.log"))
        if time.monotonic() >= deadline:
            raise TimeoutError("Scenario timed out; see " + str(run / "game-output.log"))
        time.sleep(.2)


def execute(selected: LaunchPlan, scenario: Scenario, startup_timeout: float) -> Path:
    scenario.validate_template(selected.template)
    selected.prepare()
    scenario.install(selected.run / "profile")
    # Presence opts the observer into the loading-screen continue hook; ordinary observer runs stay passive.
    (selected.run / "scenario.json").write_text(json.dumps(asdict(scenario), indent=2) + "\n")
    with (selected.run / "game-output.log").open("w") as output:
        process = subprocess.Popen(selected.command, cwd=selected.game, stdout=output, stderr=subprocess.STDOUT)
        try:
            wait_until(process, selected.run, lambda: read_status(selected.run / "profile/Lua/profile-scene.txt").startswith("READY ")
                       and read_status(selected.run / "status.txt") == "READY"
                       and read_status(selected.run / "loading-hook.txt") == "READY", startup_timeout)
            (selected.run / "scene-ready.txt").write_text(read_status(selected.run / "profile/Lua/profile-scene.txt") + "\n")
            identifier = request(selected.run, scenario.name, scenario.warmup, scenario.duration)
            capture = selected.run / "captures" / (identifier + "-" + scenario.name)
            def completed():
                status = read_status(selected.run / "status.txt")
                if status.startswith(("STOPPED ", "OVERFLOW ")):
                    raise RuntimeError("Recording ended prematurely: " + status)
                return status.startswith("COMPLETE ") and (capture / "summary.properties").is_file()
            wait_until(process, selected.run, completed, scenario.warmup + scenario.duration + 60)
            result = properties(capture / "summary.properties")
            if result["status"] != "COMPLETE" or int(result["frames"]) <= 0:
                raise RuntimeError("Recording did not produce a complete nonempty capture")
            quit_scenario(selected.run)
            if process.wait(timeout=30) != 0:
                raise RuntimeError("Game exited unsuccessfully after capture")
            return capture
        finally:
            if process.poll() is None:
                # Only terminate the process this command created, never an existing user session.
                process.terminate()
                try: process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()


@dataclass(frozen=True)
class Benchmark:
    root: Path
    scenario: Scenario
    runs: tuple[LaunchPlan, ...]
    startup_timeout: float = 180

    def describe(self) -> dict:
        return {"scenario": asdict(self.scenario), "startup_timeout": self.startup_timeout,
                "runs": [run.describe() for run in self.runs]}

    def launch(self) -> None:
        self.root.mkdir(parents=True, exist_ok=False)
        (self.root / "benchmark.json").write_text(json.dumps(self.describe(), indent=2) + "\n")
        pairs: dict[str, Path] = {}
        results = []
        try:
            for index, selected in enumerate(self.runs):
                print("Running " + selected.run.name, flush=True)
                capture = execute(selected, self.scenario, self.startup_timeout)
                pairs[selected.renderer] = capture
                results.append({"run": selected.run.name, "renderer": selected.renderer, "capture": str(capture)})
                (self.root / "results.json").write_text(json.dumps(results, indent=2) + "\n")
                if index % 2 == 1:
                    (self.root / f"comparison-{index // 2 + 1:02}.md").write_text(compare(pairs["opengl"], pairs["julia"]))
                    pairs.clear()
            (self.root / "status.txt").write_text("COMPLETE\n")
        except BaseException as failure:
            (self.root / "status.txt").write_text("ERROR " + str(failure) + "\n")
            raise
