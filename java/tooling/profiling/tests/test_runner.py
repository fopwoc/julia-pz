from __future__ import annotations

import json
import plistlib
from unittest.mock import patch
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from runner.launch import plan
from runner.control import request
from runner.bake import bake
from runner.report import compare, summarize


class RunnerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.game = self.root / "game with spaces"
        self.game.mkdir()
        (self.game / "projectzomboid.jar").write_bytes(b"fixture")
        (self.game / "jre64/bin").mkdir(parents=True)
        (self.game / "jre64/bin/java").touch()
        (self.game / "ProjectZomboid64.json").write_text(json.dumps({
            "mainClass": "zombie/gameStates/MainScreenState", "classpath": [".", "projectzomboid.jar"],
            "vmArgs": ["-Xmx3072m", "-Djava.library.path=natives/"]}))
        self.observer = self.root / "profiling-agent.jar"
        self.observer.touch()
        (self.game / "ZombieBuddy.jar").touch()
        self.buddy_home = self.root / "real home/.zombie_buddy"
        self.buddy_home.mkdir(parents=True)
        (self.buddy_home / "config.json").write_text(json.dumps(
            {"trusted_authors": [1], "preload_mods": {"JuliaDev": {"jarPath": "/real/julia-preload.jar"}}}))

    def test_planning_has_no_side_effects_and_preserves_game_settings(self):
        run = self.root / "not created"
        selected = plan(self.game, run, self.observer, "opengl")
        self.assertFalse(run.exists())
        self.assertIn("-Xmx3072m", selected.command)
        self.assertIn("-cachedir=" + str(run / "profile"), selected.command)
        self.assertNotIn("-debug", selected.command)
        self.assertEqual(2, sum(value.startswith("-javaagent:") for value in selected.command))
        self.assertEqual("opengl", selected.describe()["renderer"])

    def test_profiling_agent_precedes_buddy_and_selects_the_renderer(self):
        run = self.root / "test run"
        selected = plan(self.game, run, self.observer, "julia", debug=True, buddy_home=self.buddy_home)
        agents = [value for value in selected.command if value.startswith("-javaagent:")]
        self.assertEqual("-javaagent:" + str(self.observer) + "=julia=" + str(run), agents[0])
        self.assertTrue(agents[1].endswith("ZombieBuddy.jar"))
        self.assertIn("-Duser.home=" + str(run / "home"), selected.command)
        self.assertIn("-debug", selected.command)
        baseline = plan(self.game, self.root / "base", self.observer, "opengl", buddy_home=self.buddy_home)
        self.assertIn("-javaagent:" + str(self.observer) + "=" + str(self.root / "base"), baseline.command)
        run.mkdir()
        with self.assertRaises(ValueError): plan(self.game, run, self.observer, "opengl")

    def test_runs_isolate_preload_list_and_drop_julia_mods_from_the_active_list(self):
        template = self.root / "template"
        (template / "mods").mkdir(parents=True)
        (template / "mods/default.txt").write_text("VERSION = 1,\nmods\n{\n    mod = Other,\n    mod = Julia,\n    mod = JuliaDev,\n}\nmaps\n{\n    map = Julia,\n}\n")
        config_before = (self.buddy_home / "config.json").read_text()
        selected = plan(self.game, self.root / "run", self.observer, "julia", template=template, buddy_home=self.buddy_home)
        selected.prepare()
        active = (selected.run / "profile/mods/default.txt").read_text()
        self.assertNotIn("mod = Julia", active)
        self.assertIn("mod = Other,", active)
        self.assertIn("map = Julia,", active)
        config = json.loads((selected.run / "home/.zombie_buddy/config.json").read_text())
        self.assertEqual({}, config["preload_mods"])
        self.assertEqual([1], config["trusted_authors"])
        self.assertEqual(config_before, (self.buddy_home / "config.json").read_text())

    def test_bake_keeps_only_what_a_run_needs_and_never_overwrites(self):
        source = self.root / "Zomboid"
        save = source / "Saves/Apocalypse/world"
        save.mkdir(parents=True)
        (save / "map_ver.bin").write_bytes(b"save")
        (source / "Saves/Apocalypse/other").mkdir()
        (source / "mods/LocalMod").mkdir(parents=True)
        (source / "mods/default.txt").write_text("VERSION = 1,\n")
        (source / "mods/reset-mods-42_00.txt").write_text("marker")
        (source / "options.ini").write_text("width=1\n")
        output = bake(source, "Apocalypse/world", self.root / "template")
        self.assertTrue((output / "Saves/Apocalypse/world/map_ver.bin").is_file())
        self.assertFalse((output / "Saves/Apocalypse/other").exists())
        self.assertFalse((output / "mods/LocalMod").exists())
        self.assertTrue((output / "mods/reset-mods-42_00.txt").is_file())
        self.assertTrue((output / "options.ini").is_file())
        with self.assertRaises(ValueError): bake(source, "Apocalypse/world", output)
        with self.assertRaises(ValueError): bake(source, "Apocalypse/missing", self.root / "other")

    def test_mac_plan_reads_bundle_options_and_launch_copies_a_template_without_changing_it(self):
        app = self.root / "Project Zomboid.app"
        contents = app / "Contents"
        (contents / "Java").mkdir(parents=True)
        (contents / "Java/projectzomboid.jar").touch()
        (contents / "Info.plist").write_bytes(plistlib.dumps({"JVMOptions": ["-XstartOnFirstThread", "-Xmx3072m"],
            "JVMMainClassName": "zombie.gameStates.MainScreenState"}))
        template = self.root / "template"
        template.mkdir()
        (template / "save.bin").write_bytes(b"original save")
        run = self.root / "mac-run"
        selected = plan(app, run, self.observer, "opengl", buddy=self.game / "ZombieBuddy.jar",
                        java=self.game / "jre64/bin/java", template=template, buddy_home=self.buddy_home)
        self.assertIn("-XstartOnFirstThread", selected.command)
        with patch("runner.launch.subprocess.call", return_value=0) as execute:
            self.assertEqual(0, selected.launch())
            execute.assert_called_once()
        self.assertEqual(b"original save", (template / "save.bin").read_bytes())
        self.assertEqual(b"original save", (run / "profile/save.bin").read_bytes())
        self.assertTrue((run / "launch.json").is_file())

    def test_capture_commands_are_explicit_and_reject_unready_observer(self):
        run = self.root / "run"
        run.mkdir()
        (run / "environment.properties").touch()
        (run / "status.txt").write_text("WAITING_FOR_FRAME_OWNER\n")
        with self.assertRaises(ValueError): request(run, "forest")
        (run / "status.txt").write_text("READY\n")
        request(run, "forest", 1, 2)
        text = (run / "control.properties").read_text()
        self.assertIn("duration_ms=2000", text)
        self.assertIn("scenario=forest", text)
        request(run)
        self.assertIn("action=stop", (run / "control.properties").read_text())

    def test_comparison_rejects_incomplete_and_different_scenarios(self):
        baseline, candidate = self.root / "baseline", self.root / "candidate"
        baseline.mkdir(); candidate.mkdir()
        text = "status=COMPLETE\nscenario=forest\nwarmup_ms=100\nduration_ms=1000\nframes=10\naverage_fps=60\nmedian_ns=16000000\np95_ns=20000000\np99_ns=30000000\nmax_ns=40000000\n"
        (baseline / "summary.properties").write_text(text)
        (candidate / "summary.properties").write_text(text)
        self.assertIn("p99", compare(baseline, candidate))
        for invalid in (text.replace("COMPLETE", "STOPPED"), text.replace("forest", "urban")):
            (candidate / "summary.properties").write_text(invalid)
            with self.assertRaises(ValueError): compare(baseline, candidate)

    def test_summary_takes_medians_per_spot_and_renderer(self):
        results = []
        for index, (renderer, spot, fps) in enumerate((("opengl", "town", 60), ("julia", "town", 90), ("julia", "town", 100),
                                                       ("opengl", "town", 62), ("julia", "town", 95), ("opengl", "town", 58),
                                                       ("opengl", "forest", 40))):
            capture = self.root / f"capture-{index}"
            capture.mkdir()
            (capture / "summary.properties").write_text(f"average_fps={fps}\np99_ns=20000000\n")
            results.append({"renderer": renderer, "spot": spot, "capture": str(capture)})
        text = summarize("scene", results)
        self.assertIn("| town | opengl | 3 | 60.00 | 58.00-62.00 | 20.000 |", text)
        self.assertIn("| town | julia | 3 | 95.00 | 90.00-100.00 | 20.000 |", text)
        self.assertIn("| forest | opengl | 1 | 40.00 |", text)


if __name__ == "__main__": unittest.main()
