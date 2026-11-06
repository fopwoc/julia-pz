"""Deterministic distribution ZIPs with source/binary revision checks."""
from __future__ import annotations

import os
import tempfile
import zipfile
from pathlib import Path

from distribution.snapshot import File, Snapshot


def write_zip(output: Path, files: dict[str, File]) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(dir=output.parent, prefix=output.name + ".")
    try:
        with os.fdopen(descriptor, "wb") as stream, zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_DEFLATED,
                                                                 compresslevel=9) as archive:
            for name, content in sorted(files.items()):
                entry = zipfile.ZipInfo("Julia/" + name, date_time=(1980, 1, 1, 0, 0, 0))
                entry.create_system = 3
                entry.external_attr = content.mode << 16
                entry.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(entry, content.data)
        os.replace(temporary, output)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def workshop(snapshot: Snapshot, agent: Path, loader: Path, output: Path) -> None:
    with zipfile.ZipFile(agent) as archive:
        manifest = archive.read("META-INF/MANIFEST.MF").decode().replace("\r\n ", "")
    versions = [line.partition(": ")[2] for line in manifest.splitlines() if line.startswith("Implementation-Version: ")]
    if versions != [snapshot.version]:
        raise ValueError(f"Agent identity {versions} does not match committed source {snapshot.version}; rebuild from a clean matching revision")
    files = snapshot.workshop()
    files["libs/julia-agent.jar"] = File(agent.read_bytes())
    signature = agent.with_name(agent.name + ".zbs")
    if signature.exists():
        files["libs/julia-agent.jar.zbs"] = File(signature.read_bytes())
    files["libs/julia.dll"] = File(loader.read_bytes())
    write_zip(output, files)
