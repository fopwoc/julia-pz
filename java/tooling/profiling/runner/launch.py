"""Build launch plans from the installed game's own settings; create only disposable run profiles."""
from __future__ import annotations

import json
import os
import platform
import plistlib
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class LaunchPlan:
    command: list[str]
    game: Path
    run: Path
    template: Path | None
    renderer: str
    debug: bool

    def describe(self) -> dict:
        return {"command": self.command, "cwd": str(self.game), "run": str(self.run),
                "profile": str(self.run / "profile"), "template": str(self.template) if self.template else None,
                "renderer": self.renderer, "debug": self.debug}

    def prepare(self) -> None:
        self.run.mkdir(parents=True, exist_ok=False)
        profile = self.run / "profile"
        if self.template:
            shutil.copytree(self.template, profile, symlinks=False)
        else:
            profile.mkdir()
        (self.run / "launch.json").write_text(json.dumps(self.describe(), indent=2) + "\n")
    def launch(self) -> int:
        self.prepare()
        with (self.run / "game-output.log").open("w") as output:
            return subprocess.call(self.command, cwd=self.game, stdout=output, stderr=subprocess.STDOUT)


def plan(game: Path, run: Path, observer: Path, renderer: str, julia: Path | None = None,
         buddy: Path | None = None, java: Path | None = None, template: Path | None = None,
         debug: bool = False) -> LaunchPlan:
    game, run, observer = game.resolve(), run.resolve(), observer.resolve()
    if run.exists():
        raise ValueError("Choose a fresh run directory; existing runs are never overwritten")
    if template:
        template = template.resolve()
        if not template.is_dir() or template == run or template in run.parents:
            raise ValueError("Profile template must be an existing directory outside the run")
    if not observer.is_file():
        raise ValueError("Build :tooling:profiling:agent:jar first")
    if (game / "Project Zomboid.app").is_dir():
        game = game / "Project Zomboid.app"
    options, main, classpath = [], "zombie.gameStates.MainScreenState", [".", "projectzomboid.jar"]
    if game.suffix == ".app":
        contents = game / "Contents"
        info = plistlib.loads((contents / "Info.plist").read_bytes())
        options = list(info["JVMOptions"])
        main = info["JVMMainClassName"]
        arch = "aarch64" if platform.machine() in ("arm64", "aarch64") else "x86_64"
        java = java or contents / "PlugIns" / ("jre-" + arch) / "Contents/Home/bin/java"
        game = contents / "Java"
        options += ["-Djava.library.path=" + str(game)]
    elif (game / "ProjectZomboid64.json").is_file():
        settings = json.loads((game / "ProjectZomboid64.json").read_text())
        options = list(settings["vmArgs"])
        main = settings["mainClass"].replace("/", ".")
        classpath = settings["classpath"]
        java = java or game / "jre64/bin/java"
    else:
        raise ValueError("Supply the installed .app or Linux directory containing ProjectZomboid64.json")
    if not (game / "projectzomboid.jar").is_file() or not Path(java).is_file():
        raise ValueError("Game archive or bundled Java executable is missing")
    agents = []
    if renderer == "julia":
        if julia is None or not julia.is_file():
            raise ValueError("Julia renderer requires a built agent JAR")
        agents.append("-javaagent:" + str(julia.resolve()) + "=activate=" + str(run / "renderer"))
    elif renderer != "opengl":
        raise ValueError("Renderer must be opengl or julia")
    if buddy:
        if not buddy.is_file(): raise ValueError("ZombieBuddy agent is missing")
        agents.append("-javaagent:" + str(buddy.resolve()))
    # Place the observer after the renderer and mod agents. It uses capable transformation to preserve Julia's patches.
    agents.append("-javaagent:" + str(observer) + "=" + str(run))
    args = ["-cachedir=" + str(run / "profile")]
    if debug: args.append("-debug")
    return LaunchPlan([str(Path(java).resolve()), *options, *agents, "-cp", os.pathsep.join(classpath), main, *args],
                      game, run, template, renderer, debug)
