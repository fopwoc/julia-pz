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

from .mods import edit_active

# Whichever copy a template carries: the profiling agent decides whether Julia runs, not the mod list.
JULIA_IDS = ("Julia", "JuliaDev")


@dataclass(frozen=True)
class LaunchPlan:
    command: list[str]
    game: Path
    run: Path
    template: Path | None
    renderer: str
    debug: bool
    buddy_home: Path | None = None

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
        self._install_renderer(profile)
        (self.run / "launch.json").write_text(json.dumps(self.describe(), indent=2) + "\n")
    def _install_renderer(self, profile: Path) -> None:
        """ZombieBuddy keeps its preload list in the user's home and ignores -cachedir, so each run gets its own home:
        otherwise a baseline would still preload the player's Julia mod, and every run would rewrite their real list."""
        self.sync_mods(profile / "mods/default.txt")
        if not self.buddy_home:
            return
        home = self.run / "home/.zombie_buddy"
        if self.buddy_home.is_dir():
            shutil.copytree(self.buddy_home, home, symlinks=False)
        else:
            home.mkdir(parents=True)
        config_path = home / "config.json"
        config = json.loads(config_path.read_text()) if config_path.is_file() else {}
        config["preload_mods"] = {}
        config_path.write_text(json.dumps(config, indent=2) + "\n")

    def sync_mods(self, active: Path) -> None:
        """The profiling agent is the only Julia in a run, in both the default list and the save's, which overrides it."""
        active.parent.mkdir(parents=True, exist_ok=True)
        edit_active(active, remove=JULIA_IDS)

    def launch(self) -> int:
        self.prepare()
        with (self.run / "game-output.log").open("w") as output:
            return subprocess.call(self.command, cwd=self.game, stdout=output, stderr=subprocess.STDOUT)


def plan(game: Path, run: Path, observer: Path, renderer: str,
         buddy: Path | None = None, java: Path | None = None, template: Path | None = None,
         debug: bool = False, buddy_home: Path | None = None) -> LaunchPlan:
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
    if renderer not in ("opengl", "julia"):
        raise ValueError("Renderer must be opengl or julia")
    buddy = buddy or (game / "ZombieBuddy.jar" if (game / "ZombieBuddy.jar").is_file() else None)
    if buddy and not buddy.is_file():
        raise ValueError("ZombieBuddy agent is missing: " + str(buddy))
    # Both renderers run under ZombieBuddy when it is present, so only Julia differs. The profiling agent goes first:
    # Julia must be armed before ZombieBuddy, and the observer refuses to install after its target has loaded.
    mode = "julia=" if renderer == "julia" else ""
    agents = ["-javaagent:" + str(observer) + "=" + mode + str(run)]
    properties = []
    if buddy:
        agents.append("-javaagent:" + str(buddy.resolve()))
        properties.append("-Duser.home=" + str(run / "home"))
    args = ["-cachedir=" + str(run / "profile")]
    if debug: args.append("-debug")
    return LaunchPlan([str(Path(java).resolve()), *options, *properties, *agents, "-cp", os.pathsep.join(classpath), main, *args],
                      game, run, template, renderer, debug,
                      (buddy_home if buddy_home is not None else Path.home() / ".zombie_buddy") if buddy else None)
