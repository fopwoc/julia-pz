"""Read release sources from committed Git objects, including the two pinned renderer forks."""
from __future__ import annotations

import io
import re
import subprocess
import tarfile
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class File:
    data: bytes
    mode: int = 0o100644


def git(root: Path, *args: str) -> str:
    return subprocess.check_output(["git", "-C", str(root), *args], text=True).strip()


def archive(root: Path, revision: str) -> dict[str, File]:
    data = subprocess.check_output(["git", "-C", str(root), "archive", "--format=tar", revision])
    result = {}
    with tarfile.open(fileobj=io.BytesIO(data)) as source:
        for entry in source:
            if entry.isfile():
                result[entry.name] = File(source.extractfile(entry).read(), 0o100755 if entry.mode & 0o111 else 0o100644)
            elif entry.issym():
                result[entry.name] = File(entry.linkname.encode(), 0o120777)
    return result


def pin(root: Path, revision: str, path: str) -> str:
    entry = git(root, "ls-tree", revision, "--", path).split()
    if len(entry) != 4 or entry[:2] != ["160000", "commit"]:
        raise ValueError(f"Missing pinned submodule: {path} at {revision}")
    return entry[2]


class Snapshot:
    def __init__(self, root: Path, revision: str = "HEAD"):
        self.root = root
        self.commit = git(root, "rev-parse", "--verify", revision + "^{commit}")
        self.tag = next((tag for tag in git(root, "tag", "--points-at", self.commit, "--sort=-version:refname").splitlines()
                         if re.fullmatch(r"\d+\.\d+\.\d+", tag)), "")
        self.number = int(git(root, "rev-list", "--count", self.commit))
        self.angelica = pin(root, self.commit, "third_party/angelica")
        renderer = root / "third_party/angelica"
        self.gtnhlib = pin(renderer, self.angelica, "third_party/gtnhlib")

    @property
    def version(self) -> str:
        return (self.tag + "-" if self.tag else "") + self.commit[:8]

    def manifest(self, dirty: bool = False) -> bytes:
        rows = [("Julia", "julia-pz", self.commit), ("Angelica", "Angelica", self.angelica),
                ("GTNHLib", "GTNHLib", self.gtnhlib)]
        text = "# Source revisions\n\n"
        if dirty:
            text += "Local development build: the checkout has uncommitted changes. These revisions identify its committed base, not those changes.\n\n"
        text += "| Component | Exact source | Download |\n| --- | --- | --- |\n"
        for name, repo, commit in rows:
            url = "https://github.com/fopwoc/" + repo
            text += f"| {name} | [`{commit}`]({url}/tree/{commit}) | [ZIP]({url}/archive/{commit}.zip) |\n"
        if self.tag:
            url = f"https://github.com/fopwoc/julia-pz/releases/download/{self.tag}/julia-{self.tag}-sources.zip"
            text += f"\n[Complete source archive, including the required fork sources]({url}).\n"
        else:
            text += "\nThe matching `julia-sources.zip` is distributed alongside this build in the CI artifacts.\n"
        text += """
The Workshop package contains Julia's own sources. The complete source archive additionally expands
Angelica's `glsm`, `sdl-gpu`, renderer tests, native patches and version catalog, and GTNHLib's selected
sources and license. Unrelated Minecraft modules and Git history are omitted.

To rebuild from Git, check out the Julia revision above and run `git submodule update --init --recursive`.
To rebuild from the complete source ZIP, extract it and build directly; its fork sources are already present.
Use JDK 25 and the Gradle wrapper: `./java/gradlew -p java check :agent:jar`.
For all native platforms, use `./tools/build-universal.sh` on macOS with Docker and the Apple SDK installed.
Gradle and CMake download pinned external dependencies; this is not an offline dependency bundle.
See `THIRD_PARTY.md` and `licenses/` for licensing and replacement information.
"""
        return text.encode()

    def identity(self) -> bytes:
        return f"commit={self.commit}\ntag={self.tag}\nbuildNumber={self.number}\n".encode()

    def sources(self) -> dict[str, File]:
        result = archive(self.root, self.commit)
        renderer = self.root / "third_party/angelica"
        for name, content in archive(renderer, self.angelica).items():
            if name.startswith(("glsm/", "sdl-gpu/", "renderer-tests/")) or name in (
                    "LICENSE", "README.MD", "AGENTS.md", ".gitmodules", "gradle/libs.versions.toml"):
                result["third_party/angelica/" + name] = content
        lib = archive(renderer / "third_party/gtnhlib", self.gtnhlib)
        listed = lib["pz-sources.txt"].data.decode().splitlines()
        selected = {"src/main/java/" + line.strip() for line in listed if line.strip() and not line.startswith("#")}
        selected.update(name for name in lib if name.startswith("src/pz/java/"))
        selected.update(("pz-sources.txt", "LICENSE.txt", "README.md", "AGENTS.md"))
        for name in selected:
            result["third_party/angelica/third_party/gtnhlib/" + name] = lib[name]
        result["SOURCES.md"] = File(self.manifest())
        result[".source-info.properties"] = File(self.identity())
        return result

    def workshop(self) -> dict[str, File]:
        files = archive(self.root, self.commit)
        excluded = {".gitignore", ".gitmodules", "AGENTS.md", "logo.pxd"}
        result = {name: content for name, content in files.items()
                  if name not in excluded and not name.startswith((".github/", "third_party/"))}
        result["SOURCES.md"] = File(self.manifest())
        return result
