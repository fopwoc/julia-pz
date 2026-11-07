"""Prepares a reusable profile template from one existing save, so a batch needs no hand-copied profile."""
from __future__ import annotations

import shutil
from pathlib import Path

DEFAULT_TEMPLATE = Path.home() / "Zomboid/profiling-template"


def bake(source: Path, save: str, output: Path) -> Path:
    world = source / "Saves" / save
    if not (world / "map_ver.bin").is_file():
        raise ValueError("No save at " + str(world))
    if output.exists():
        raise ValueError("Delete the existing template first: " + str(output))
    (output / "mods").mkdir(parents=True)
    shutil.copytree(world, output / "Saves" / save, symlinks=False)
    if (source / "options.ini").is_file():
        shutil.copy2(source / "options.ini", output / "options.ini")
    # PZ resets the default mod list when the marker for the running game version is missing.
    for name in ("default.txt", *(path.name for path in (source / "mods").glob("reset-mods-*.txt"))):
        if (source / "mods" / name).is_file():
            shutil.copy2(source / "mods" / name, output / "mods" / name)
    return output
