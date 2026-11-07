"""Edits PZ's ActiveMods files (default.txt, a save's mods.txt) in disposable profiles."""
from __future__ import annotations

import re
from pathlib import Path

EMPTY = "VERSION = 1,\nmods\n{\n}\nmaps\n{\n}\n"


def edit_active(path: Path, add: tuple[str, ...] = (), remove: tuple[str, ...] = ()) -> None:
    text = path.read_text() if path.exists() else EMPTY
    # PZ's format has separate mods and maps blocks, so an edit must never land in the maps block.
    matches = list(re.finditer(r"\bmods\s*\{([^{}]*)\}", text))
    if len(matches) != 1:
        raise ValueError("Unrecognized ActiveMods file: " + str(path))
    block = matches[0][1]
    for mod in remove:
        block = re.sub(r"[ \t]*\bmod\s*=\s*" + re.escape(mod) + r"\s*,?[ \t]*\n", "", block)
    for mod in add:
        if not is_active(block, mod):
            block += "    mod = " + mod + ",\n"
    path.write_text(text[:matches[0].start(1)] + block + text[matches[0].end(1):])


def is_active(block: str, mod: str) -> bool:
    return re.search(r"\bmod\s*=\s*" + re.escape(mod) + r"\s*[,\n]", block) is not None
