# Julia

<img src="common/poster.png" alt="Julia" width="160" align="right">

Julia is a Project Zomboid (Build 42) client mod that takes the game's OpenGL renderer and runs it on native graphics
APIs. It relies on [Angelica](https://github.com/GTNewHorizons/Angelica)'s GL state manager (GLSM) and its SDL3 GPU
backend.

Game code and mods keep calling plain LWJGL OpenGL. Julia rewrites those calls as classes load, so frames are drawn
with **Metal** on macOS and, later, **Direct3D 12** on Windows and **Vulkan** on Linux. No OpenGL driver is involved.

## How it works

### Startup: all or nothing

```mermaid
flowchart TD
    start(["Game starts with Julia"]) --> scan["Scan the game and enabled mods for graphics calls"]
    scan --> prepare["Prepare each graphics class in memory:<br/>redirect GL → GLSM, apply Julia bridges,<br/>turn backend calls into typed invokedynamic"]
    prepare --> gate{"Every class ready,<br/>exactly one frame owner?"}
    gate -- yes --> runtime["Start the isolated runtime:<br/>own LWJGL 3.4 + SDL3 + Angelica,<br/>GPU device, borrowed game window"]
    runtime --> load["Game classes load already rewritten"]
    gate -- no --> vanilla["Leave the game on its own OpenGL renderer<br/>and report why"]
```

Julia never rewrites part of the game. A single unsupported call, an ambiguous frame anchor, or a class that would
pass native types across the boundary keeps the whole game on vanilla OpenGL. You get a report instead of a broken
frame.

### Every frame

```mermaid
sequenceDiagram
    participant PZ as Game render thread
    participant RD as RenderDispatch
    participant GLSM as Angelica GLSM + Julia bridges
    participant GPU as SDL3 GPU (Metal)
    PZ->>RD: frame begin (injected before sprite rendering)
    PZ->>RD: glBindTexture, glUniform*, glDrawArrays …
    RD->>GLSM: same calls, bound once per session
    GLSM->>GPU: tracked state → pipelines, uploads, draw commands
    PZ->>RD: present (injected before Display.update)
    RD->>GPU: submit command buffer, present to the window
    Note over PZ: GLFW keeps the window, events, and input
```

- **Isolation.** The game ships its own LWJGL. Julia's graphics stack lives in a separate class loader, and only JDK
  types cross into it. Calls are `invokedynamic` sites bound to the current render session, so no game class ever links
  against Julia's LWJGL.
- **Shaders.** The game's GLSL is compiled by Angelica to SPIR-V and translated to the native shading language.
- **Window.** The game keeps its GLFW window. Julia receives the native handle (Cocoa, Win32, X11 or Wayland), wraps it
  in an SDL window, and puts back the window state it changed when it detaches.
- **Failure.** An exception inside a frame aborts the GPU frame before it propagates, so nothing half-recorded is ever
  presented.

## Status

Julia is in its integration stage. An **experimental startup agent** redirects vanilla rendering to Metal; the normal
ZombieBuddy mod entry still runs probes. The startup path is not ready for gameplay. What works today:

- Inside the running game, the mod runs self-verifying probes on PZ's render thread: a Metal device baseline, the full
  GLSM adapter drawing through redirected OpenGL calls, and an experimental presentation into the game window. Each
  probe checks its output pixel by pixel and then restores the game's GL state.
- Offline preparation of vanilla reports 182/182 graphics consumers structurally ready and
  zero static blockers. Structural readiness does not establish rendering correctness; startup activation is explicit.
- Debug callbacks now cross the isolated runtime through a typed Java method handle. Windowless checks verify SDL
  validation-relay delivery, UTF-8 messages, full-width user pointers, replacement, unregistration and shutdown cleanup.
  Messages are injected into the relay for this check; actual driver diagnostics depend on validation being enabled.
  Only the observed direct GL43/KHR registration form is bridged; other callback APIs remain outside this coverage.
- The current offline checks pass 125 JVM tests and verify 37,760 pixels on windowless Metal. The latest bridges have not
  been tested inside PZ.
- ImGui draw handoffs stay on the game side, calling the prepared renderer and then freeing the owned draw data.
  The archive gate requires that renderer to be included and prepared. A separate windowless probe uses the supplied
  archive's actual ImGui JNI and GL renderer: 2,048 pixels verify font-atlas sampling, texture switching, clipping,
  2× framebuffer scale, repeated draws and caller-state restoration. Detached platform viewports and in-game startup
  lifecycle are not covered by this probe.
- Program-resource queries reflect linked SPIR-V storage blocks and buffer variables, recovering original GL bindings
  from Angelica's remapped descriptor tables. Checks cover struct arrays, matrix and array strides, cross-stage blocks,
  runtime arrays, compute read/write bindings, truncated outputs and cache invalidation after relinking. Only the three
  observed query overloads and these two interfaces are bridged. Geometry stages, block instance arrays,
  multidimensional/specialization arrays and nested arrays of structs are explicitly rejected.
- Texture views support the full-image, same-format 2D path used by PZ: names share storage, sampler and aspect state
  stay independent, and deleting a parent preserves surviving views. Shader sampling of the stencil aspect uses a GPU
  extraction fallback, verified for all 256 values and after writes through a view. This correctness-first path records
  256 extraction draws per consumed source texture per draw; it is not a performance-ready implementation. Mip/layer
  subsets, format reinterpretation and compute dispatch with bound stencil-aspect textures are explicitly rejected.
  Julia also replaces GLSM's Minecraft texture-shrinking deletion workaround and loads the bundled backend shaders
  without Minecraft. Native calls zero-extend stencil references to preserve values 128–255 on macOS ARM64.
- The startup agent preflights classpath archives, rejects changed or unplanned graphics consumers, opens its isolated
  backend on the first render-context call, and closes it before the host window is destroyed. Initialization uploads
  survive the first frame boundary. Live vanilla testing reaches the menu through Metal; the host's logical render size
  is scaled to the Retina drawable. Full-size UI and aligned input are user-confirmed. Purple menu video, modular-shader
  linking failures remain runtime blockers. Ranged indexed quads now use the existing quad conversion, with both triangle
  halves verified offscreen for every index width. Invalid matrix uploads return a GL error before touching staging,
  preventing the observed bone-palette overflow from aborting the renderer. A live retry no longer froze, but the map
  was black and character/world views were black or flickering. Failed character shaders remain unresolved. An
  additional world-entry exception came from a capability read on the game-logic thread. Capability flags now live in
  an immutable shared snapshot with backend-scoped publication; only GPU operations enter the render-thread dispatch.
  Cross-thread reads, lazy startup, revocation and GPU-thread enforcement pass offline; this fix awaits a live retry.
  No gameplay claim is made.
- Viewpoint and other mods that use modern OpenGL come after vanilla.

| Platform | GPU API | State |
| --- | --- | --- |
| macOS | Metal | Device and adapter verified in-game; window presentation experimental |
| Windows | Direct3D 12 | Planned; the platform seams exist |
| Linux | Vulkan | Planned; the platform seams exist |

## Building

The repository root is the mod folder (`common/`, `42/mod.info`). The Gradle build lives in `java/`. You need
JDK 25, and the build works on macOS and Linux only.

```sh
cd java
./gradlew check                    # tests; never launches the game or opens a window
./gradlew assemble                 # → ../42/media/java/client/Julia.jar, plus modules/zomboid-mod/build/distributions/Julia.zip
```

After `assemble`, the repository folder is a complete mod. Symlink or copy it into the PZ `mods` directory as `Julia`.

These native probes run only when requested and open no window:

```sh
./gradlew :probe:renderProbe       # raw Metal device: clear + translated textured draw
./gradlew :probe:adapterProbe      # full GLSM adapter on redirected OpenGL calls
./gradlew :probe:suiteProbe        # both, in the same order as the mod
./gradlew :probe:installDist       # standalone probe CLI; does not install the mod
JAVA_OPTS="-XstartOnFirstThread --enable-native-access=ALL-UNNAMED" \
  modules/probe/build/install/probe/bin/probe --imgui-adapter /tmp/imgui-probe /path/to/game.jar /path/to/natives
```

These offline audits read JAR bytecode and never load game classes:

```sh
./gradlew :probe:installDist
modules/probe/build/install/probe/bin/probe --prepare-runtime preparation.tsv projectzomboid.jar
modules/probe/build/install/probe/bin/probe --audit-redirects --compat redirects.tsv projectzomboid.jar
```

Logging defaults to `error`. Set `LOG_LEVEL=warn|info|debug` to see more.

## In the game

Julia needs the ZombieBuddy mod to load its Java code. Approve `Julia.jar` when ZombieBuddy asks. Results are written
to `<PZ cache>/julia/run-<pid>/`.

| JVM property | Effect |
| --- | --- |
| `-Djulia.probe=window` | Experimental: present into the game window (default `adapter`, offscreen) |
| `-Djulia.outputDir=…`, `-Djulia.modJar=…` | Override the output root and archive location for test launches |

## License

Julia's own code is [WTFNMFPL](LICENSE). The bundled Angelica, LWJGL3ify, and RetroFuturaBootstrap libraries are LGPL v3.
See [THIRD_PARTY.md](THIRD_PARTY.md).

> [!NOTE]
> This project contains AI-generated code. See [AI_USAGE.md](AI_USAGE.md) for details.

### Experimental startup test

Build `./gradlew :agent:jar`. Launch the game with
`-javaagent:/absolute/path/julia-agent.jar=activate=/absolute/path/new-report-directory`, using a separate game profile.
The agent keeps its graphics libraries nested and does not add its LWJGL to the game classpath. Test with an explicit
vanilla classpath: the standard launcher may also include separate tools such as ZombieBuddy's native ImGui approval UI,
which appropriately fail whole-input preparation. This experimental path refuses startup when preparation is blocked.
Do not load it alongside the ZombieBuddy probe mod or use an existing save for validation.
