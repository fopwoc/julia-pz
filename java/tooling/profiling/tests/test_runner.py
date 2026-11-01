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
from runner.report import compare


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
        self.julia = self.root / "julia.jar"
        self.julia.touch()

    def test_planning_has_no_side_effects_and_preserves_game_settings(self):
        run = self.root / "not created"
        selected = plan(self.game, run, self.observer, "opengl")
        self.assertFalse(run.exists())
        self.assertIn("-Xmx3072m", selected.command)
        self.assertIn("-cachedir=" + str(run / "profile"), selected.command)
        self.assertNotIn("-debug", selected.command)
        self.assertEqual(1, sum(value.startswith("-javaagent:") for value in selected.command))
        self.assertEqual("opengl", selected.describe()["renderer"])

    def test_julia_and_buddy_precede_observer_and_profile_is_disposable(self):
        run = self.root / "test run"
        selected = plan(self.game, run, self.observer, "julia", self.julia, self.julia, debug=True)
        agents = [value for value in selected.command if value.startswith("-javaagent:")]
        self.assertTrue(agents[0].endswith("=activate=" + str(run / "renderer")))
        self.assertTrue(agents[-1].startswith("-javaagent:" + str(self.observer)))
        self.assertIn("-debug", selected.command)
        run.mkdir()
        with self.assertRaises(ValueError): plan(self.game, run, self.observer, "opengl")

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
        selected = plan(app, run, self.observer, "opengl", java=self.game / "jre64/bin/java", template=template)
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


if __name__ == "__main__": unittest.main()
