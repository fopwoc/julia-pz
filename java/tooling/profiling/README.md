# In-game profiling tooling

A development tool, not part of the Workshop mod. One command launches the game with and without Julia, records the same
spots in a save, exits, and compares the results.

`core` is a timing recorder with externally supplied timestamps. `agent` builds `profiling-agent.jar`, a separate Julia
entry used only for measurements: it holds the frame observer and Julia's startup, so both sides of a comparison launch
the same jar and one argument picks the renderer. `<run>` observes native OpenGL and `julia=<run>` also starts Julia.
The Python `runner` builds launch plans from the installed macOS bundle or Linux JSON settings and controls each run.
Probe executables remain separate at `java/tooling/probe`.

## Quick start

Make a single-player save with a living character, accepted startup dialogs, and the graphics settings and mods you want
to test. Then, from the repository root with the game closed:

```sh
./java/gradlew -p java :tooling:profiling:agent:jar
python3 java/tooling/profiling/profile.py bake Apocalypse/my-save
python3 java/tooling/profiling/profile.py run java/tooling/profiling/scenes/example.json
```

`bake` copies that save, `options.ini` and the mod list files to `~/Zomboid/profiling-template`, without changing the
originals. It leaves out `mods/` folders, so local mods must be in the save's list and installed elsewhere. Edit the
scene's `save` to match. **`run` launches the game** several times and needs the machine to itself. Add `--dry-run` to
see every launch without starting or writing anything. The summary prints at the end, and everything is under
`java/tooling/profiling/build/runs/`.

## Scenes

```json
{
  "name": "muldraugh",
  "save": "Apocalypse/my-save",
  "warmup": 20,
  "duration": 60,
  "repetitions": 3,
  "spots": [
    {"name": "forest", "position": [1000, 2000, 0], "hour": 12, "ghost": true},
    {"name": "horde", "position": [3000, 4000, 0], "direction": "SE"}
  ]
}
```

Each launch visits every spot in order and records it. Spot `name` must be unique. `position`, `direction` and `hour`
are optional; without any `spots` it records wherever the save left the player. `direction` is the character's facing,
not a first-person camera angle, and this tool has no camera routes, ViewPoint controls or scripted weather.

The character is always invincible, so a run cannot end in a death. `ghost` also stops zombies reacting, for steadier
scenes; leave it off where zombie load is what you are measuring. Weather, streaming and simulation can still vary, so
read the FPS range across repetitions before trusting a difference.

## What a batch does

For each repetition, in alternating renderer order, the runner:

1. Copies the template to a fresh `<run>/profile`, with its own home folder holding a copy of ZombieBuddy's settings.
2. Enables a Lua companion in the copied mod lists and removes any Julia mod from them.
3. Loads the save through PZ's normal validation and mod-reset path, and continues the loading screen when its own checks
   allow it, without simulated input.
4. For each spot, moves the player, waits for the current square, then records warmup and duration.
5. Requires complete nonempty captures, requests a normal game exit, and starts the next copy.

ZombieBuddy runs in both modes when `ZombieBuddy.jar` is beside the game, so only Julia differs. The separate home matters
because ZombieBuddy keeps its preload list there: without it a baseline would still load an installed Julia mod, and the
batch would rewrite the player's real list. A Julia run also fails unless Julia reports it is rendering, so a batch can't
quietly compare OpenGL with itself.

Runs set `focusloss=false` so they keep simulating in the background. Other graphics settings and the template are
preserved. An unresolved dialog, dead character, invalid hook, crash, frozen renderer or timeout fails the batch and no
later run starts. `--startup-timeout` defaults to 300 seconds. On error or Ctrl-C the runner stops only the process it
created. It never creates saves, approves Java mods, or modifies installed game files. Java mods in a copied profile
still go through ZombieBuddy's normal approval using the copied settings, so approve them in a normal launch first.

The batch writes `summary.md`, `results.json`, `benchmark.json`, a final `status.txt`, and one `comparison-NN.md` per
completed pair. Match FPS limits and VSync: capped results don't show maximum throughput, so check neither side is sitting at the
display's refresh rate. Treat first-visit streaming and warmed stationary measurements
as separate scenes.

## Manual runs

`plan` prints a single launch as JSON without doing anything. `launch` starts it passively, with no companion or loading
hook. Prepare the view yourself, then control it from another terminal:

```sh
python3 java/tooling/profiling/profile.py launch --run /tmp/forest-opengl --renderer opengl \
  --profile-template ~/Zomboid/profiling-template
python3 java/tooling/profiling/profile.py record /tmp/forest-opengl --scenario forest --warmup 20 --duration 60
python3 java/tooling/profiling/profile.py stop /tmp/forest-opengl
```

The first rendered frame starts warmup; after warmup, the first completed frame sets the interval baseline. `stop` and game
shutdown keep partial results as `STOPPED`, and buffer exhaustion gives `OVERFLOW`; neither is a valid full run. `--debug`
adds the game's debug flag, which changes the code that runs, so results with it aren't comparable to normal play.

An offline bytecode audit checks the hooks against the installed game without loading its classes:

```sh
java -cp java/tooling/profiling/agent/build/libs/profiling-agent.jar \
  dev.fopwoc.julia.profiling.agent.FrameAudit /path/to/projectzomboid.jar
```

`./java/gradlew -p java :tooling:profiling:toolingZip` also builds a portable ZIP under `build/distributions/`.

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

The agent test uses a synthetic JVM fixture, the runner tests mock process execution, and the Lua companion runs against
mocked game APIs when `lua` is installed. None of them launch the game or open a window, and Julia mode is only exercised
in a real run.
