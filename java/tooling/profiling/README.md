# In-game profiling tooling

A development tool, not part of the Workshop mod. One command plays the same save twice, first on plain OpenGL with
nothing intercepted and then with Julia translating, and compares the two.

`core` is a timing recorder. `agent` builds `profiling-agent.jar`, which holds everything: the commands below, a frame
observer, the code that drives the game, and Julia's startup. `profile.sh` and `profile.ps1` only find the game and its
bundled Java and pass the command to the jar, so nothing else needs installing.

## Quick start

Make a single-player save with a living character, accepted startup dialogs, and the graphics settings and mods you want
to test. Approve any Java mods in a normal launch. Then, with the game closed, from the repository root:

```sh
./java/gradlew -p java :tooling:profiling:agent:jar
java/tooling/profiling/profile.sh bake Apocalypse/my-save
java/tooling/profiling/profile.sh run my-scene.json
```

`bake` copies that save, `options.ini` and the mod list files to `~/Zomboid/profiling-template`, and doesn't change the
originals. It leaves out `mods/` folders, so mods in the list must be installed from the Workshop.

**`run` launches the game.** It prints the comparison at the end and keeps everything in `~/Zomboid/profiling/`. Add
`--dry-run` to see the launch commands without starting or writing anything. `--repetitions n` repeats the pair, alternating
which renderer goes first; one pair is quick, but can't separate a real difference from run-to-run noise. Set `PZ_GAME` if
the game isn't in Steam's default folder.

## Scenes

Plain scenes need nothing but the save: no ZombieBuddy and no Steam, so they compare just the renderer.

```json
{
  "name": "muldraugh",
  "save": "Apocalypse/my-save",
  "warmup": 20,
  "duration": 60,
  "jvmOptions": ["-Xmx8g"],
  "spots": [
    {"name": "forest", "position": [1000, 2000, 0], "hour": 12, "ghost": true},
    {"name": "horde", "position": [3000, 4000, 0]}
  ]
}
```

`"zombieBuddy": true` and `"steam": true` turn those back on, for example to test Workshop Java mods. Only the mods in the
save's own list run, even at the main menu. A mod that needs newer GL than the platform has can't be compared this way: on
macOS, Viewpoint crashes the OpenGL run because Apple's OpenGL lacks what it asks for.

Each launch visits every spot in order and records it. Spot names must be unique, and without any `spots` it records
wherever the save left the player. These launches start Java directly, so Steam launch options don't apply: put heap
settings in `jvmOptions`, which are added after the game's own and so override them. `scenes/example.json` is a starting
point.

The character is always invincible, so a run can't end in a death. `ghost` also stops zombies reacting, for steadier scenes;
leave it off where zombie load is what you're measuring. There are no camera routes, ViewPoint controls or scripted
weather. Weather, streaming and simulation can still vary, so repeat a pair before trusting a small difference.

## What a run does

For each renderer, the jar:

1. Copies the template to a fresh profile, sets its active mods to the save's own list minus any Julia mod, and with
   ZombieBuddy gives the run its own home folder holding a copy of its settings with an empty preload list.
2. Starts the game with `-javaagent:profiling-agent.jar=opengl,<run>` or `=julia,<run>`, followed by ZombieBuddy if the
   scene asks for it. Only Julia differs between the two.
3. Waits for the main menu, then loads the save through the game's own continue path, so validation and mod resets match a
   player's. The loading screen continues when its own checks allow it, without simulated input.
4. For each spot, moves the player, waits for the current square, then records warmup and duration.
5. Quits normally and starts the next run.

The separate home matters because ZombieBuddy keeps its preload list there and ignores the game's `-cachedir`. Without it, a
baseline would still load an installed Julia mod, and every run would rewrite your real list. Before each recording, the
agent also checks the renderer is the expected one, so a run can't quietly compare OpenGL with itself.

Runs set `focusloss=false` so they keep simulating in the background. Other settings and the template are preserved.
An error, crash or timeout stops the batch, and it ends only the process it created. It never creates saves, approves Java
mods, or modifies installed game files. The automation calls game classes by name, so a game update can break it; that
fails with an error in the report rather than a wrong number.

## Results

Each run folder has `automation.txt` (its progress or error), `game-output.log`, `status.txt`, and, for Julia, `renderer/`
with the usual reports. Each spot has `captures/<spot>/`:

- `frames.csv`: elapsed capture time, completed-frame interval, and render-step wall duration, in nanoseconds.
- `summary.properties`: frame count, average FPS from measured intervals, and nearest-rank median/p95/p99/max.
- `gc.properties`: whole-JVM collection count and time, including warmup.
- `options.ini`: the profile's settings when recording started.

The batch writes `summary.md`, the table it prints. `compare <capture> <capture>` builds the same table from any two
captures of one spot, and refuses incomplete recordings or different timings.

The frame hook preallocates its buffers and does no per-frame file IO. Its wall durations can include waits and presentation
blocking, so they are **not GPU timestamps** or total simulation CPU time. Check neither side is sitting at the display's
refresh rate, since a capped result doesn't show maximum throughput. Matching spot names don't prove identical game state.

## Verification

```sh
./java/gradlew -p java check
java/tooling/profiling/profile.sh audit /path/to/projectzomboid.jar
```

`audit` checks the hooks against the installed game without loading its classes. Tests use synthetic class fixtures and a
fake game, and none of them launch the game or open a window. The automation's calls into the real game are only exercised by
a real run. `./java/gradlew -p java :tooling:profiling:toolingZip` builds a portable ZIP under `build/distributions/`.
