from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from runner.scenario import Scenario
from runner.benchmark import Benchmark, execute, wait_until
from runner.launch import LaunchPlan


class ScenarioTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.template = self.root / "template"
        save = self.template / "Saves/Sandbox/fixture"
        save.mkdir(parents=True)
        (save / "map_ver.bin").write_bytes(b"fixture save")
        self.config = self.root / "scenario.json"

    def scenario(self, **changes):
        data = {"name": "forest", "save": "Sandbox/fixture", "position": [100, 200, 0], "direction": "SE"}
        data.update(changes)
        self.config.write_text(json.dumps(data))
        return Scenario.read(self.config)

    def test_validates_scene_and_save_before_launch(self):
        self.scenario().validate_template(self.template)
        for changes in ({"save": "../fixture"}, {"save": "Sandbox/../fixture"}, {"save": "Multiplayer/fixture"},
                        {"warmup": -1}, {"duration": 0}, {"position": [1, 2, .5]}, {"direction": "oops"},
                        {"hour": float("nan")}, {"unexpected": True}):
            with self.subTest(changes=changes), self.assertRaises(ValueError): self.scenario(**changes)
        with self.assertRaises(ValueError): self.scenario(save="Sandbox/missing").validate_template(self.template)

    def test_companion_changes_only_copy_and_preserves_existing_mods_and_maps(self):
        (self.template / "mods").mkdir()
        active = "VERSION = 1,\nmods\n{\n    mod = Existing,\n}\nmaps\n{\n    map = ExistingMap,\n}\n"
        (self.template / "mods/default.txt").write_text(active)
        (self.template / "Saves/Sandbox/fixture/mods.txt").write_text(active)
        (self.template / "options.ini").write_text("width=1800\nfocusloss=true\nframeRate=60\n")
        copied = self.root / "copy"
        shutil.copytree(self.template, copied)
        self.scenario().install(copied)
        self.assertEqual(active, (self.template / "mods/default.txt").read_text())
        self.assertIn("focusloss=true", (self.template / "options.ini").read_text())
        self.assertEqual("width=1800\nfocusloss=false\nframeRate=60\n", (copied / "options.ini").read_text())
        for path in (copied / "mods/default.txt", copied / "Saves/Sandbox/fixture/mods.txt"):
            text = path.read_text()
            self.assertIn("mod = Existing,", text)
            self.assertIn("mod = ProfilingScenario,", text.split("maps")[0])
            self.assertEqual(active.split("maps")[1], text.split("maps")[1])
        self.assertTrue((copied / "mods/ProfilingScenario/common/media/lua/client/ProfilingConfig.lua").is_file())
        with self.assertRaises(ValueError): self.scenario().install(copied)

    def test_adapter_lifecycle_with_mocked_game_apis(self):
        lua = shutil.which("lua")
        if not lua: self.skipTest("Lua unavailable; install Lua to run the adapter fixture")
        fixture = Path(__file__).with_name("scenario_fixture.lua")
        source = Path(__file__).resolve().parents[1] / "scenario/ProfilingScenario.lua"
        result = subprocess.run([lua, str(fixture), str(source)], text=True, capture_output=True)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_wait_fails_on_exit_error_and_timeout(self):
        from unittest.mock import Mock
        process = Mock()
        process.poll.return_value = 1
        with self.assertRaises(RuntimeError): wait_until(process, self.root, lambda: False, 1)
        process.poll.return_value = None
        with self.assertRaises(TimeoutError): wait_until(process, self.root, lambda: False, 0)
        (self.root / "status.txt").write_text("ERROR broken hook")
        with self.assertRaisesRegex(RuntimeError, "broken hook"):
            wait_until(process, self.root, lambda: True, 1)

    def test_execute_waits_for_scene_and_capture_then_requests_normal_exit(self):
        from unittest.mock import Mock
        run = self.root / "run"
        selected = LaunchPlan(["synthetic-java"], self.root, run, self.template, "opengl", False)
        process = Mock()
        process.poll.return_value = None
        process.wait.side_effect = lambda **kwargs: setattr(process.poll, "return_value", 0) or 0
        stages = []
        def wait(process, folder, predicate, timeout):
            stages.append(timeout)
            if len(stages) == 1:
                self.assertFalse(predicate())
                active = run / "profile/mods/default.txt"
                original = active.read_text()
                active.write_text("VERSION = 1,\nmods\n{\n}\n")
                with self.assertRaisesRegex(RuntimeError, "removed the scenario companion"):
                    predicate()
                active.write_text(original)
                (run / "status.txt").write_text("READY\n")
                (run / "environment.properties").touch()
                (run / "profile/Lua/profile-scene.txt").write_text("READY 100 200 0\n")
                self.assertFalse(predicate())  # All hooks, not just the Lua scene, must be installed.
                (run / "loading-hook.txt").write_text("READY\n")
                self.assertTrue(predicate())
            else:
                command = dict(line.split("=", 1) for line in (run / "control.properties").read_text().splitlines())
                self.assertEqual("forest", command["scenario"])
                capture = run / "captures" / (command["id"] + "-forest")
                capture.mkdir(parents=True)
                (capture / "summary.properties").write_text("status=COMPLETE\nframes=20\n")
                (run / "status.txt").write_text("COMPLETE " + capture.name + "\n")
                self.assertTrue(predicate())
        with patch("runner.benchmark.subprocess.Popen", return_value=process), patch("runner.benchmark.wait_until", side_effect=wait):
            capture = execute(selected, self.scenario(), 180)
        self.assertTrue((capture / "summary.properties").is_file())
        self.assertEqual("quit\n", (run / "profile/Lua/profile-command.txt").read_text())
        self.assertTrue((run / "scenario.json").is_file())
        process.terminate.assert_not_called()
        self.assertEqual([180, 140], stages)

    def test_execute_terminates_only_its_process_on_failed_handshake(self):
        from unittest.mock import Mock
        run = self.root / "run"
        selected = LaunchPlan(["synthetic-java"], self.root, run, self.template, "opengl", False)
        process = Mock()
        process.poll.return_value = None
        with patch("runner.benchmark.subprocess.Popen", return_value=process), patch("runner.benchmark.wait_until", side_effect=TimeoutError("no scene")):
            with self.assertRaises(TimeoutError): execute(selected, self.scenario(), 1)
        process.terminate.assert_called_once()
        self.assertFalse((run / "profile/Lua/profile-command.txt").exists())

    def test_benchmark_plan_lists_alternating_runs_without_writes_or_launch(self):
        from test_runner import RunnerTest
        fixture = RunnerTest()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        self.scenario()
        run = self.root / "never-created"
        cli = Path(__file__).resolve().parents[1] / "profile.py"
        result = subprocess.run([sys.executable, str(cli), "benchmark-plan", "--game", str(fixture.game),
                                 "--run", str(run), "--profile-template", str(self.template), "--scenario", str(self.config),
                                 "--observer", str(fixture.observer), "--julia", str(fixture.julia), "--repetitions", "2"],
                                text=True, capture_output=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["opengl", "julia", "julia", "opengl"], [run["renderer"] for run in json.loads(result.stdout)["runs"]])
        self.assertFalse(run.exists())
        self.assertFalse((self.template / "mods").exists())

    def test_repetition_pairs_remain_correct_when_order_alternates(self):
        root = self.root / "benchmark"
        runs = tuple(LaunchPlan([], self.root, root / str(i), self.template, renderer, False)
                     for i, renderer in enumerate(("opengl", "julia", "julia", "opengl")))
        captures = [self.root / (str(i) + "-capture") for i in range(4)]
        with patch("runner.benchmark.execute", side_effect=captures) as run, patch("runner.benchmark.compare", return_value="comparison") as compare:
            Benchmark(root, self.scenario(), runs).launch()
        self.assertEqual(4, run.call_count)
        self.assertEqual([(captures[0], captures[1]), (captures[3], captures[2])], [call.args for call in compare.call_args_list])
        self.assertEqual("COMPLETE\n", (root / "status.txt").read_text())

    def test_failed_run_stops_batch_and_records_error(self):
        root = self.root / "benchmark"
        selected = LaunchPlan([], self.root, root / "first", self.template, "opengl", False)
        with patch("runner.benchmark.execute", side_effect=RuntimeError("crash")) as run:
            with self.assertRaisesRegex(RuntimeError, "crash"):
                Benchmark(root, self.scenario(), (selected, selected)).launch()
        self.assertEqual(1, run.call_count)
        self.assertIn("ERROR crash", (root / "status.txt").read_text())


if __name__ == "__main__": unittest.main()
