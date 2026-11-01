"""Atomic commands for a running observer; also usable from a future scenario adapter."""
from __future__ import annotations

import os
import math
import re
import tempfile
import uuid
from pathlib import Path


def request(run: Path, scenario: str | None = None, warmup: float = 20, duration: float = 60) -> str:
    if not (run / "environment.properties").is_file():
        raise ValueError("No profiling observer found in this run directory")
    status = (run / "status.txt").read_text().split()[0]
    if scenario and status not in ("READY", "COMPLETE", "STOPPED", "OVERFLOW"):
        raise ValueError("Observer is not ready for a new capture: " + status)
    values = {"id": uuid.uuid4().hex, "action": "start" if scenario else "stop"}
    if scenario:
        if not re.fullmatch(r"[A-Za-z0-9_.-]{1,64}", scenario) or not math.isfinite(warmup) or not math.isfinite(duration) or warmup < 0 or duration < .001:
            raise ValueError("File-safe scenario name, nonnegative warmup, and positive duration required")
        values.update(scenario=scenario, warmup_ms=str(round(warmup * 1000)), duration_ms=str(round(duration * 1000)))
    descriptor, temporary = tempfile.mkstemp(dir=run, prefix="control.")
    try:
        with os.fdopen(descriptor, "w") as stream:
            stream.write("".join(key + "=" + value + "\n" for key, value in values.items()))
        os.replace(temporary, run / "control.properties")
    finally:
        if os.path.exists(temporary): os.unlink(temporary)
    return values["id"]
