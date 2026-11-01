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

Scene positioning, camera motion, time/weather control, auto-loading saves, and repeated-run orchestration are not yet
automated. Start with two manually prepared fixtures: a stationary dense forest and a stationary urban intersection.
Use copies of the same save, view, resolution, mods, weather and graphics settings. Record streaming/first-visit runs
separately from warmed stationary views. Match FPS limits and VSync; capped results do not show maximum throughput.

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
No tests launch the game or a graphics window. Actual in-game collection still needs a manual test.
