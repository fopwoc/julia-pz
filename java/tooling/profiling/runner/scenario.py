"""Validated stationary scenes and a disposable Lua companion, independent of the renderer."""
from __future__ import annotations

import json
import math
import re
from dataclasses import dataclass
from pathlib import Path

MOD_ID = "ProfilingScenario"


@dataclass(frozen=True)
class Scenario:
    name: str
    save: str
    warmup: float = 20
    duration: float = 60
    position: tuple[float, float, int] | None = None
    direction: str | None = None
    hour: float | None = None

    @classmethod
    def read(cls, path: Path) -> "Scenario":
        data = json.loads(path.read_text())
        if not isinstance(data, dict) or set(data) - {"name", "save", "warmup", "duration", "position", "direction", "hour"}:
            raise ValueError("Unknown scenario fields")
        name, save = data.get("name", ""), data.get("save", "")
        if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9_.-]{1,64}", name):
            raise ValueError("Scenario needs a file-safe name")
        if not isinstance(save, str) or not re.fullmatch(r"[^/\\\x00-\x1f]+/[^/\\\x00-\x1f]+", save) or any(p in (".", "..") for p in save.split("/")):
            raise ValueError("Save must be Mode/SaveName, without traversal")
        if save.split("/")[0] in ("Multiplayer", "LastStand"):
            raise ValueError("Only existing single-player saves are supported")
        def number(value):
            return type(value) in (int, float) and math.isfinite(value)
        warmup, duration = data.get("warmup", 20), data.get("duration", 60)
        if not number(warmup) or not number(duration) or warmup < 0 or duration < .001:
            raise ValueError("Nonnegative warmup and positive duration required")
        position = data.get("position")
        if position is not None:
            if not isinstance(position, list) or len(position) != 3 or not all(number(n) for n in position) or position[0] < 0 or position[1] < 0 or type(position[2]) is not int or not -32 <= position[2] <= 31:
                raise ValueError("Position must be [x, y, integer z] in the game world")
            position = tuple(position)
        direction, hour = data.get("direction"), data.get("hour")
        if direction is not None and direction not in ("N", "NE", "E", "SE", "S", "SW", "W", "NW"):
            raise ValueError("Invalid compass direction")
        if hour is not None and (not number(hour) or not 0 <= hour < 24):
            raise ValueError("Hour must be in [0, 24)")
        return cls(name, save, warmup, duration, position, direction, hour)

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
        fields = {"mode": self.save.split("/")[0], "save": self.save.split("/")[1],
                  "direction": self.direction, "hour": self.hour}
        if self.position:
            fields.update(zip(("x", "y", "z"), self.position))
        literal = ",\n".join(key + " = " + json.dumps(value, ensure_ascii=False) for key, value in fields.items() if value is not None)
        (scripts / "ProfilingConfig.lua").write_text("return {\n" + literal + "\n}\n")
        for path in (profile / "mods/default.txt", profile / "Saves" / self.save / "mods.txt"):
            text = path.read_text() if path.exists() else "VERSION = 1,\nmods\n{\n}\nmaps\n{\n}\n"
            # PZ's ActiveMods format has separate mods and maps blocks. Never append to the maps block.
            matches = list(re.finditer(r"\bmods\s*\{([^{}]*)\}", text))
            if len(matches) != 1:
                raise ValueError("Unrecognized ActiveMods file: " + str(path))
            match = matches[0]
            if re.search(r"\bmod\s*=\s*" + MOD_ID + r"\s*[,\n]", match[1]):
                raise ValueError("Template already enables the reserved profiling companion")
            text = text[:match.end(1)] + "    mod = " + MOD_ID + ",\n" + text[match.end(1):]
            path.write_text(text)
        (profile / "Lua").mkdir(exist_ok=True)
        for filename in ("profile-scene.txt", "profile-command.txt"):
            (profile / "Lua" / filename).unlink(missing_ok=True)
