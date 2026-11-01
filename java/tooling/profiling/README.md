# In-game profiling tooling

This is a separate development tool, not a Workshop mod or a production-agent dependency. It works with native
OpenGL and Julia. The observer uses only JDK 25 APIs; it does not need Angelica, ZombieBuddy, or native graphics libraries.

`core` is a callable timing recorder with externally supplied monotonic timestamps. `agent` adapts PZ's
`RenderThread.lockStepRenderStep()Z`, counting only returns that report a rendered frame. The Python `runner` builds
launch plans from the installed macOS bundle or Linux JSON settings, copies disposable profiles, controls recordings,
and compares reports. Probe executables remain available separately at `java/tooling/probe` through the usual `:probe` tasks.

## Build and inspect

From the repository root:

```sh
./java/gradlew -p java :tooling:profiling:agent:jar
# Optional standalone ZIP: observer, callable core JAR, runner and instructions
./java/gradlew -p java :tooling:profiling:toolingZip
python3 java/tooling/profiling/profile.py plan \
  --game '/path/to/ProjectZomboid' --run /tmp/forest-opengl --renderer opengl
```

`plan` prints JSON and does not create a profile or launch a process. On macOS, `--game` accepts the installed `.app`
or its parent directory. On Linux it accepts the directory containing `ProjectZomboid64.json`. The bundled JVM,
main class, memory/GC flags and classpath are reused. `--java` overrides the JVM; JDK 25 is required.

The standalone ZIP is written under `java/tooling/profiling/build/distributions/`; extract it and run its `profile.py`.
Use `--julia` explicitly when running outside the source checkout.

An offline bytecode audit reads resources without initializing game classes:

```sh
java -cp java/tooling/profiling/agent/build/libs/profiling-agent.jar \
  dev.fopwoc.julia.profiling.agent.FrameAudit /path/to/projectzomboid.jar
```

## Run and control

The following command **launches the game**. Use it manually when ready:

```sh
python3 java/tooling/profiling/profile.py launch \
  --game '/path/to/ProjectZomboid' --run /tmp/forest-opengl \
  --renderer opengl --profile-template /path/to/prepared-test-profile
```

The run directory must not exist. The template is copied to `<run>/profile`; the original profile and saves are never
modified. Without a template, a fresh empty profile is created. This starts Java directly instead of invoking Steam's
launch button; the game's own Steam/native runtime requirements still apply.

For Julia, replace `--renderer opengl` with `--renderer julia`. `--julia /path/to/julia-agent.jar` overrides its default
build output. `--buddy /path/to/ZombieBuddy.jar` adds ZombieBuddy in either mode. The observer is placed after these
agents. Julia reports are routed into `<run>/renderer`, separate from normal game reports. `--debug` adds the game's debug flag; it is not needed for timing. Match it between compared runs.

Load the same test save and prepare the same view, then use another terminal:

```sh
python3 java/tooling/profiling/profile.py record /tmp/forest-opengl --scenario forest --warmup 20 --duration 60
python3 java/tooling/profiling/profile.py stop /tmp/forest-opengl
```

The first rendered frame starts warmup. After warmup, the first completed frame establishes the interval baseline;
subsequent completed frames are sampled. Results flush on the controller thread after the window ends. `stop` and game
shutdown preserve partial results as `STOPPED`; buffer exhaustion produces `OVERFLOW`. Neither is a valid full run.
An observer installation/control error is reported in `status.txt` and stderr; no renderer recovery is attempted.

## Automated comparisons

Prepare a single-player profile with an existing living character, accepted startup dialogs, the desired graphics
settings and mods, and a stationary test view. Both renderers must support that fixture. Write a scenario JSON:

```json
{
  "name": "forest",
  "save": "Sandbox/benchmark-save",
  "warmup": 20,
  "duration": 60
}
```

`save` is relative to the profile's `Saves/` folder. Optional `position: [x, y, z]` teleports the player after loading;
`direction: "SE"` sets their facing, and `hour: 12` sets the initial time of day. Omit them to preserve the saved state.
Facing is not a first-person camera angle. This first adapter supports stationary vanilla scenes; camera routes,
ViewPoint controls, scripted weather and deterministic simulation replay are not implemented. Weather, zombies,
streaming and simulation can still vary: use a suitable prepared save and inspect repeated results.

Inspect the whole batch without launching or writing anything:

```sh
python3 java/tooling/profiling/profile.py benchmark-plan \
  --game '/path/to/ProjectZomboid' --run /tmp/forest-comparison \
  --profile-template /path/to/prepared-test-profile --scenario /path/to/forest.json \
  --repetitions 3
```

Replace **`benchmark-plan` with `benchmark`** to explicitly launch the batch. It creates a fresh profile copy for every
run, alternates OpenGL/Julia order between repetitions, and performs:

1. Enable a development-only Lua companion in the copied default/save mod lists.
2. Load the selected save through PZ's normal save validation and mod-reset path.
3. Continue the loading screen when its original loading checks permit it, without simulated input.
4. Position the player, wait for a loaded current square, then arm the observer's warmup and recording.
5. Require a complete nonempty capture, request normal game exit, and proceed to the next fresh copy.

The companion and loading-screen hook are enabled only for benchmark runs. Ordinary `launch`/`record` stays passive.
No `-debug` flag is required for this adapter. An unresolved dialog, missing/dead character, invalid hook, crash, frozen
renderer or timeout fails the batch; no later repetition is launched. `--startup-timeout` defaults to 180 seconds.
On error or Ctrl-C the runner terminates only the process it created. Use this while no other PZ session is active.
It does not create saves, approve Java mods, or modify installed game files. Prepare those prerequisites manually.

The batch writes `benchmark.json`, `results.json`, a final `status.txt`, and one `comparison-NN.md` for each completed
pair. Per-run `scenario.json`, `scene-ready.txt`, `loading-hook.txt` and `profile/Lua/profile-scene.txt` record the scene
contract and its handshake. Original templates are never changed, and all captures remain available after a failure.
Match FPS limits and VSync; capped results do not show maximum throughput. Treat first-visit streaming and warmed
stationary measurements as separate scenarios.

## Results

Each run contains `launch.json`, `environment.properties`, `status.txt`, and `game-output.log`. Each recording under
`captures/<request>-<scenario>/` contains:

- `frames.csv`: elapsed capture time, completed-frame interval, and render-step wall duration, in nanoseconds.
- `summary.properties`: frame count, average FPS from measured intervals, and nearest-rank median/p95/p99/max.
- `gc.properties`: whole-JVM collection count/time changes from arming to completion polling, including warmup.
- `options.ini`: a snapshot of the disposable profile's settings at arming, when present.

The frame hook preallocates bounded sample buffers; it performs no per-frame file IO or sample-object allocation.
The method's wall duration can include waits and presentation blocking. These are **not GPU timestamps** or total
simulation CPU time. Intervals are measured between completed render steps; capture bounds are not partial-frame samples.

Compare two completed capture directories:

```sh
python3 java/tooling/profiling/profile.py compare \
  /tmp/forest-opengl/captures/<id>-forest /tmp/forest-julia/captures/<id>-forest
```

The report rejects incomplete/empty captures and mismatched scenario names or timing plans. Matching names do not prove
identical game state: inspect `options.ini`, launch/environment metadata, and your prepared fixtures too.

## Verification

```sh
./java/gradlew -p java check
python3 -m unittest discover -s java/tooling/profiling/tests
```

The agent integration test runs a synthetic JVM fixture, not PZ. The direct-launch tests mock process execution.
The scenario adapter also has a Lua fixture with mocked game APIs (install `lua` to run it); batch tests use synthetic
processes, never PZ. `FrameAudit` verifies both frame and loading hooks against the installed archive without loading
game classes. The adapter follows the installed game's `MainScreen.continueLatestSave` path and public
[PZ Lua file APIs](https://projectzomboid.com/modding/zombie/Lua/LuaManager.GlobalObject.html).
No tests launch the game or a graphics window. Automated in-game collection still needs its first manual smoke test.
