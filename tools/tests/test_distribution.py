"""Exercise real committed trees and nested gitlinks without touching the user's repositories."""
from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from distribution.bundle import workshop, write_zip
from distribution.snapshot import Snapshot, git


def put(root: Path, name: str, text: str) -> None:
    path = root / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)


def init(root: Path) -> None:
    root.mkdir(parents=True, exist_ok=True)
    subprocess.run(["git", "init", "-q", str(root)], check=True)
    git(root, "config", "user.email", "test@example.invalid")
    git(root, "config", "user.name", "Packaging test")
    git(root, "config", "advice.addEmbeddedRepo", "false")


def commit(root: Path) -> str:
    git(root, "add", "-A")
    git(root, "commit", "-qm", "snapshot")
    return git(root, "rev-parse", "HEAD")


class DistributionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "repo"
        init(self.root)
        self.renderer = self.root / "third_party/angelica"
        init(self.renderer)
        self.lib = self.renderer / "third_party/gtnhlib"
        init(self.lib)
        for name in ("LICENSE.txt", "README.md", "AGENTS.md"):
            put(self.lib, name, name)
        put(self.lib, "pz-sources.txt", "# selected upstream files\nexample/Helper.java\n")
        put(self.lib, "src/main/java/example/Helper.java", "selected original")
        put(self.lib, "src/main/java/example/Minecraft.java", "not built")
        put(self.lib, "src/pz/java/example/Owned.java", "owned implementation")
        commit(self.lib)
        for name in ("LICENSE", "README.MD", "AGENTS.md", ".gitmodules", "gradle/libs.versions.toml"):
            put(self.renderer, name, name)
        for name in ("glsm/src/Renderer.java", "sdl-gpu/native/CMakeLists.txt", "renderer-tests/Test.java"):
            put(self.renderer, name, name)
        put(self.renderer, "src/main/Minecraft.java", "not built")
        commit(self.renderer)
        for name in ("README.md", "LICENSE", ".workshopignore", "java/build.gradle.kts", "common/status.lua"):
            put(self.root, name, name)
        put(self.root, "java/tooling/probe/Probe.java", "development tool")
        put(self.root, "tools/build.sh", "#!/bin/sh\nexit 0\n")
        (self.root / "tools/build.sh").chmod(0o755)
        put(self.root, ".github/workflows/build.yml", "CI")
        self.revision = commit(self.root)
        self.snapshot = Snapshot(self.root)
        self.agent = Path(self.temporary.name) / "agent.jar"
        with zipfile.ZipFile(self.agent, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", "Implementation-Version: " + self.snapshot.version + "\r\n")
        self.loader = Path(self.temporary.name) / "julia.dll"
        self.loader.write_bytes(b"test loader")

    def test_source_archive_expands_pinned_revisions_and_is_reproducible(self) -> None:
        put(self.lib, "src/main/java/example/Helper.java", "newer unpinned implementation")
        commit(self.lib)
        files = self.snapshot.sources()
        selected = "third_party/angelica/third_party/gtnhlib/src/main/java/example/Helper.java"
        self.assertEqual(b"selected original", files[selected].data)
        self.assertIn("third_party/angelica/sdl-gpu/native/CMakeLists.txt", files)
        self.assertIn("third_party/angelica/third_party/gtnhlib/src/pz/java/example/Owned.java", files)
        self.assertNotIn("third_party/angelica/src/main/Minecraft.java", files)
        self.assertNotIn("third_party/angelica/third_party/gtnhlib/src/main/java/example/Minecraft.java", files)
        self.assertIn(self.revision.encode(), files[".source-info.properties"].data)
        first, second = Path(self.temporary.name) / "a.zip", Path(self.temporary.name) / "b.zip"
        write_zip(first, files)
        write_zip(second, files)
        self.assertEqual(first.read_bytes(), second.read_bytes())
        with zipfile.ZipFile(first) as archive:
            self.assertEqual(0o100755, archive.getinfo("Julia/tools/build.sh").external_attr >> 16)
            self.assertEqual(sorted(archive.namelist()), archive.namelist())
            self.assertTrue(all(entry.date_time == (1980, 1, 1, 0, 0, 0) for entry in archive.infolist()))

    def test_workshop_keeps_julia_source_and_manifest_but_omits_forks_and_ci(self) -> None:
        target = Path(self.temporary.name) / "workshop.zip"
        workshop(self.snapshot, self.agent, self.loader, target)
        with zipfile.ZipFile(target) as archive:
            self.assertIn("Julia/java/build.gradle.kts", archive.namelist())
            self.assertNotIn("Julia/java/tooling/probe/Probe.java", archive.namelist())
            self.assertIn("Julia/libs/julia-agent.jar", archive.namelist())
            self.assertIn("Julia/.workshopignore", archive.namelist())
            self.assertNotIn("Julia/.github/workflows/build.yml", archive.namelist())
            self.assertFalse(any("/third_party/" in name for name in archive.namelist()))
            manifest = archive.read("Julia/SOURCES.md").decode()
            for sha in (self.revision, self.snapshot.angelica, self.snapshot.gtnhlib):
                self.assertIn(sha, manifest)

    def test_mismatched_or_dirty_agent_does_not_replace_existing_output(self) -> None:
        target = Path(self.temporary.name) / "workshop.zip"
        target.write_bytes(b"keep previous package")
        for version in ("unrelated", self.snapshot.version + "-dirty"):
            with zipfile.ZipFile(self.agent, "w") as archive:
                archive.writestr("META-INF/MANIFEST.MF", "Implementation-Version: " + version + "\n")
            with self.assertRaises(ValueError):
                workshop(self.snapshot, self.agent, self.loader, target)
            self.assertEqual(b"keep previous package", target.read_bytes())

    def test_tagged_manifest_names_the_complete_release_source_asset(self) -> None:
        git(self.root, "tag", "0.0.3")
        snapshot = Snapshot(self.root)
        self.assertIn("/releases/download/0.0.3/julia-0.0.3-sources.zip", snapshot.manifest().decode())
        self.assertEqual("0.0.3-" + self.revision[:8], snapshot.version)
        self.assertIn("uncommitted changes", snapshot.manifest(dirty=True).decode())


if __name__ == "__main__":
    unittest.main()
