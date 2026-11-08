# Development

How Julia fits together is in [ARCHITECTURE.md](ARCHITECTURE.md).

## Building

Requirements: JDK 25 and CMake 3.24+. On macOS, also the Xcode command-line tools. The first build downloads a
SHA-256-pinned SDL archive, patches a copy, and builds `SDL3Julia` for the host; later builds are incremental.

Initialize the renderer fork after cloning (or clone with `--recurse-submodules`):

```sh
git submodule update --init --recursive
```

```sh
cd java
./gradlew check                  # Julia and renderer CPU tests; never launches the game or opens a window
./gradlew :renderer-tests:test   # only the fork-owned renderer regressions
./gradlew :preload:jar           # → modules/preload/build/libs/julia-preload.jar, the ZombieBuddy mod
./gradlew :agent:jar             # → modules/agent/build/libs/julia-agent.jar, the standalone agent
./gradlew :workshop:modZip       # → modules/workshop/build/distributions/Julia.zip
./gradlew :workshop:sourceZip    # → modules/workshop/build/distributions/Julia-sources.zip
../tools/deploy-dev.sh           # → signed JuliaDev mod in ~/Zomboid/mods, usable beside a Workshop subscription
```

Host builds carry Julia's SDL build for the host only. To include SDL builds made on other hosts, pass
`-PjuliaNatives=<dir>`, where `<dir>` holds `<os>/<arch>/<library>`, for example `linux/x64/libSDL3Julia.so`.

For a build with every platform, run `./tools/build-universal.sh` from the repository root on macOS with Docker
running. It builds the macOS libraries with the Apple SDK and the Linux and Windows ones in a container, runs the
checks, and packages both jars under `java/modules/{preload,agent}/build/libs/`.

## GPU probes

Windowless GPU checks run only on request. They create a real device but open no window:

```sh
./gradlew :probe:suiteProbe   # raw device baseline, then the full translated GL feature suite
./gradlew :probe:renderProbe  # raw device: clear and one translated textured draw
./gradlew :probe:adapterProbe # the full suite without the raw baseline
```

Reports land in `tooling/probe/build/reports/`. Run `suiteProbe` after any change to class loading, bytecode patches or
native extensions; unit tests cannot cover those paths.

For focused mip-alpha, atlas-indexing and fragment-discard checks, build `:probe:installDist`, then run
`tooling/probe/build/install/probe/bin/probe --sampling <output-dir>` with `-Djulia.gpu=vulkan` in `JAVA_OPTS` when
selecting Vulkan explicitly.

Vulkan checks should also run with `VK_LAYER_KHRONOS_validation` enabled through `VK_INSTANCE_LAYERS`. Enable
synchronization checks with `VK_LAYER_ENABLES=VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT`; ordinary
validation does not cover all memory hazards. Any validation error is a failed check even if the pixels match.

## Offline audits

These read jar bytecode and never load game classes:

```sh
./gradlew :probe:installDist
tooling/probe/build/install/probe/bin/probe --prepare-runtime preparation.tsv projectzomboid.jar
tooling/probe/build/install/probe/bin/probe --audit-redirects --compat redirects.tsv projectzomboid.jar
```

## Profiling

[java/tooling/profiling](java/tooling/profiling/README.md) runs the same save with OpenGL and with Julia and compares
frame times. Results so far are in [BENCHMARKS.md](BENCHMARKS.md).

## Flags

| Flag | Where | Effect |
| --- | --- | --- |
| `-javaagent:julia-agent.jar=activate` | Launch options, standalone | Render through Julia; reports go to a fresh `~/Zomboid/julia/run-*/` |
| `-javaagent:julia-agent.jar=activate=<dir>` | Launch options, standalone | Same, with reports in `<dir>` |
| `-javaagent:julia-agent.jar=<report.tsv>` | Launch options, standalone | Audit only: record which classes make graphics calls into a new file; rendering is unchanged |
| `-agentlib:julia=<options>` | Launch options, Windows | Same as `-javaagent:julia-agent.jar=<options>`; needs `julia.dll` beside the jar |
| `-Dangelica.sdlgpu.debug=true` | Launch options or probe JVM | GPU validation; Metal also records encoder errors and enables shader validation (slow) |
| `-Djulia.drawTrace=true` | Launch options or probe JVM | Bounded CPU draw-state snapshots and compiler inputs beside renderer/probe reports; no GPU readbacks |
| `-Dangelica.debug.glEvents=true` | Launch options or probe JVM | Turns tracing on and records each clear, clear color, framebuffer bind and rasterizer-discard change to `gl-events.txt`, with the game or mod code that made it; `draw-trace.request` in the run folder restarts the capture. Also lists how each prepared class loaded in `prepared-classes.txt` |
| `-Djulia.gpu=metal\|vulkan` | Launch options or probe JVM | Override the platform's default SDL GPU driver |
| `LOG_LEVEL=debug\|info\|warn\|error` | Environment | Julia and renderer log detail on stderr; default `error` |
| `-PjuliaNatives=<dir>` | Gradle | Pack other hosts' SDL builds (`<os>/<arch>/<library>`) into the jars |
| `-Djulia.probe.width=<px>`, `-Djulia.probe.height=<px>` | Probe JVM | Offscreen size of the adapter probe; default 32 × 32 |

Native GPU errors are also saved as `gpu-errors.txt` in the current run directory, including Metal command and encoder
details when GPU validation is enabled. GPU assertions log their condition and abort without an SDL dialog.

With draw tracing enabled, create `draw-trace.request` inside the current run directory to restart capture at the next
frame boundary. This replaces `draw-trace.txt` and resets its snapshot quota, so a stable in-game defect can be captured
after startup. Matching vertex and fragment compiler inputs are stored in `draw-shaders/` (at most 8 MiB per capture).

With tracing enabled, an explicit `texture-capture.request` file requests raw texture downloads on the render thread.
Each line contains a GL texture name and mip level, for example `7290 0`. Allocated 2D RGBA8, RGBA16F and RGBA32F
images are supported; a request is limited to 32 images and 64 MiB. Files land in `textures-<frame>/` as
`<texture>-mip<level>-<width>x<height>.rgba`, with four unsigned bytes per pixel in native texture row order.
Floating-point files use `.rgba16f` or `.rgba32f` and retain native-endian half/full-float channels. This
separate operation synchronizes the GPU and may briefly stall; ordinary draw tracing performs no GPU readbacks.
