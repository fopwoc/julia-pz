"""Validated stationary scenes and a disposable Lua companion, independent of the renderer."""
from __future__ import annotations

import json
import math
import re
from dataclasses import dataclass
from pathlib import Path

from .mods import edit_active, is_active

MOD_ID = "ProfilingScenario"


SAFE_NAME = r"[A-Za-z0-9_.-]{1,64}"


def number(value) -> bool:
    return type(value) in (int, float) and math.isfinite(value)


@dataclass(frozen=True)
class Spot:
    name: str
    position: tuple[float, float, int] | None = None
    direction: str | None = None
    hour: float | None = None
    # Ghost mode keeps zombies from reacting at all; leave it off where their load is what is being measured.
    ghost: bool = False

    @classmethod
    def read(cls, data: dict) -> "Spot":
        if not isinstance(data, dict) or set(data) - {"name", "position", "direction", "hour", "ghost"}:
            raise ValueError("Unknown spot fields")
        name = data.get("name", "")
        if not isinstance(name, str) or not re.fullmatch(SAFE_NAME, name):
            raise ValueError("Spot needs a file-safe name")
        position = data.get("position")
        if position is not None:
            if not isinstance(position, list) or len(position) != 3 or not all(number(n) for n in position) or position[0] < 0 or position[1] < 0 or not float(position[2]).is_integer():
                raise ValueError("Position must be [x, y, integer z] in the game world")
            position = tuple(position)
        direction, hour = data.get("direction"), data.get("hour")
        if direction is not None and direction not in ("N", "NE", "E", "SE", "S", "SW", "W", "NW"):
            raise ValueError("Invalid compass direction")
        if hour is not None and (not number(hour) or not 0 <= hour < 24):
            raise ValueError("Hour must be in [0, 24)")
        if type(data.get("ghost", False)) is not bool:
            raise ValueError("Ghost must be true or false")
        return cls(name, position, direction, hour, data.get("ghost", False))


@dataclass(frozen=True)
class Scenario:
    name: str
    save: str
    warmup: float = 20
    duration: float = 60
    repetitions: int = 3
    spots: tuple[Spot, ...] = ()

    @classmethod
    def read(cls, path: Path) -> "Scenario":
        data = json.loads(path.read_text())
        if not isinstance(data, dict) or set(data) - {"name", "save", "warmup", "duration", "repetitions", "spots"}:
            raise ValueError("Unknown scenario fields")
        name, save = data.get("name", ""), data.get("save", "")
        if not isinstance(name, str) or not re.fullmatch(SAFE_NAME, name):
            raise ValueError("Scenario needs a file-safe name")
        if not isinstance(save, str) or not re.fullmatch(r"[^/\\\x00-\x1f]+/[^/\\\x00-\x1f]+", save) or any(p in (".", "..") for p in save.split("/")):
            raise ValueError("Save must be Mode/SaveName, without traversal")
        if save.split("/")[0] in ("Multiplayer", "LastStand"):
            raise ValueError("Only existing single-player saves are supported")
        warmup, duration = data.get("warmup", 20), data.get("duration", 60)
        if not number(warmup) or not number(duration) or warmup < 0 or duration < .001:
            raise ValueError("Nonnegative warmup and positive duration required")
        repetitions = data.get("repetitions", 3)
        if type(repetitions) is not int or not 1 <= repetitions <= 100:
            raise ValueError("Use 1..100 repetitions")
        listed = data.get("spots", [{"name": name}])
        if not isinstance(listed, list) or not listed:
            raise ValueError("Spots must be a nonempty list")
        spots = tuple(Spot.read(spot) for spot in listed)
        if len({spot.name for spot in spots}) != len(spots):
            raise ValueError("Spot names must be unique")
        return cls(name, save, warmup, duration, repetitions, spots)

    def validate_template(self, template: Path | None) -> None:
        if template is None or not (template / "Saves" / self.save / "map_ver.bin").is_file():
            raise ValueError("Scenario requires a prepared profile containing Saves/" + self.save + "/map_ver.bin")

    def install(self, profile: Path) -> None:
        root = profile / "mods" / MOD_ID
        if root.exists():
            raise ValueError("Template already contains the reserved profiling companion")
        (root / "42").mkdir(parents=True)
        (root / "42/mod.info").write_text("name=Profiling scenario adapter\nid=" + MOD_ID + "\n")
        scripts = root / "common/media/lua/client"
        scripts.mkdir(parents=True)
        source = Path(__file__).resolve().parents[1] / "scenario/ProfilingScenario.lua"
        (scripts / "ProfilingScenario.lua").write_bytes(source.read_bytes())
        (scripts / "ProfilingConfig.lua").write_text(self._lua_config())
        for path in (profile / "mods/default.txt", profile / "Saves" / self.save / "mods.txt"):
            if path.exists() and is_active(path.read_text(), MOD_ID):
                raise ValueError("Template already enables the reserved profiling companion")
            edit_active(path, add=(MOD_ID,))
        # The runner has no input focus; background pausing would measure an idle simulation instead of the scene.
        options = profile / "options.ini"
        text = options.read_text() if options.exists() else ""
        if re.search(r"^focusloss=.*$", text, flags=re.MULTILINE):
            text = re.sub(r"^focusloss=.*$", "focusloss=false", text, flags=re.MULTILINE)
        else:
            text = text.rstrip("\n") + "\nfocusloss=false\n"
        options.write_text(text)
        (profile / "Lua").mkdir(exist_ok=True)
        for filename in ("profile-scene.txt", "profile-command.txt"):
            (profile / "Lua" / filename).unlink(missing_ok=True)

    def _lua_config(self) -> str:
        def table(fields: dict) -> str:
            return "{" + ", ".join(key + " = " + json.dumps(value, ensure_ascii=False) for key, value in fields.items() if value is not None) + "}"
        spots = []
        for spot in self.spots:
            fields = {"direction": spot.direction, "hour": spot.hour, "ghost": spot.ghost or None}
            if spot.position:
                fields.update(zip(("x", "y", "z"), spot.position))
            spots.append("    " + table(fields))
        return ("return {\nmode = " + json.dumps(self.save.split("/")[0], ensure_ascii=False) + ",\nsave = "
                + json.dumps(self.save.split("/")[1], ensure_ascii=False) + ",\nspots = {\n" + ",\n".join(spots) + "\n}\n}\n")
