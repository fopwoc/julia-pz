# Julia

A Java compatibility project exploring Project Zomboid rendering through Angelica's graphics libraries and SDL-GPU, with Metal as the first target.

Julia is in its initial integration stage. It does not yet replace Zomboid's renderer.

## Plan

1. Run vanilla Zomboid through the shared graphics layer.
2. Extend compatibility for Viewpoint and other modern OpenGL mods.

The intended dependency chain is Julia → Angelica `sdl-gpu` / `glsm` → SDL3 → Metal. Published artifacts need verification outside Minecraft before we can rely on them unchanged.

## Development

Install a JDK 25. The Gradle wrapper pins Gradle 9.8.1 and verifies the distribution checksum.

```sh
./gradlew check :gl-audit:installDist
modules/gl-audit/build/install/gl-audit/bin/gl-audit \
  --output build/reports/graphics.tsv /path/to/projectzomboid.jar /path/to/Viewpoint.jar
```

`gl-audit` reads JAR bytecode without loading game classes. It records direct method calls, field accesses, lambda implementation handles, and loaded method handles with exact JVM descriptors. Multi-release JARs use the running JDK's selected class variants. Embedded LWJGL/LWJGLX classes are excluded so binding implementation details do not inflate the inventory.

Reports contain archive SHA-256 identifiers and caller locations. They are static inventories, not proof that every referenced feature executes on macOS. Reflection, JNI calls, dynamically generated classes, and shader behavior require later investigation.

See [the first audit](docs/graphics-audit.md) for the measured game/mod surface.

Build identity comes from Git. macOS is the native development environment for the Metal integration. Tests and offline audits do not launch the game.

## Modules

- `gl-audit`: offline graphics bytecode inventory and report writer.
- `agent`: startup Java agent; currently observes loaded `zombie/`, `viewpoint/`, and `imgui/` classes without rewriting them. Its report lists references in loaded classes, not calls proven to have executed. A separate-JVM smoke test verifies attachment and application behavior.
- `backend-probe`: checks published Angelica linkage, audits redirection, and explicitly runs Metal device and full GLSM adapter probes.
- `zomboid-mod`: packages Julia as a ZombieBuddy client mod with an isolated graphics runtime.

## Backend investigation

```sh
./gradlew :backend-probe:installDist
modules/backend-probe/build/install/backend-probe/bin/backend-probe /path/to/projectzomboid.jar
modules/backend-probe/build/install/backend-probe/bin/backend-probe \
  --audit-redirects build/reports/redirects.tsv /path/to/projectzomboid.jar /path/to/Viewpoint.jar
```

The linkage probe currently exits with status 1 because integration types are missing. This is a measured compatibility result, not a successful renderer startup. See [the integration findings](docs/angelica-integration.md).

## Audit agent

```sh
./gradlew :agent:jar
LOG_LEVEL=info java -javaagent:modules/agent/build/libs/julia-agent.jar=/path/to/new-report.tsv \
  -cp /path/to/application.jar example.Main
```

The report path is required and must not exist. Reports are written at normal JVM shutdown. The agent does not modify classes or choose a renderer. It has not been installed into or tested inside Zomboid. Default `LOG_LEVEL` is `error`; accepted overrides are `warn`, `info`, and `debug`. Agent logging only configures Julia's logger namespace.

Dependency versions are locked. Refresh deliberately with Gradle's `--write-locks` option. Angelica remains a replaceable external dependency; see [third-party notices](THIRD_PARTY.md).

## Native Metal probe

```sh
./gradlew :backend-probe:renderProbe
```

This explicit task initializes SDL video on the Cocoa main thread and creates Angelica's real `Device`. It verifies a 32×32 clear, then uploads a 4×4 asymmetric texture and draws a fullscreen triangle using OpenGL GLSL 330 transformed and linked by Angelica, compiled to SPIR-V, and translated to Metal. The fragment shader swaps red and blue. GPU readback checks all 2,048 pixels across both passes. Results and PNGs are saved under `modules/backend-probe/build/reports/metal/`. It opens no window and is not part of the normal test suite.

## ZombieBuddy mod

```sh
./gradlew :zomboid-mod:modZip
```

The installable package is `modules/zomboid-mod/build/distributions/Julia.zip`. Extract it into the PZ mods directory, enable Julia and ZombieBuddy, restart PZ, and approve Julia's JAR when ZombieBuddy asks.

Julia schedules the device baseline and full adapter suite on PZ's render context, then restores host GL dispatch before returning. Its JAR includes the probe dependencies as nested JARs; a platform-parent class loader and a separate native extraction path keep the probe's LWJGL runtime separate from the game's classes. Results are written to `<PZ cache>/julia/run-<pid>/` by default. `julia.modJar` and `julia.outputDir` JVM properties can specify the archive and output root for an isolated test launch.

This first mod does not replace the game renderer or transform game graphics calls. It tests the actual Angelica device, translated shaders, and texture sampling inside the game process. Full GLSM startup and the redirected offscreen draw completed their GPU checks, but the PZ process then crashed because the probe left native GL dispatch changed. The corrected build restores host dispatch and has passed a runtime retest with user-confirmed world rendering and controls. See [the correction](docs/host-gl-crash.md). Presentation and gameplay rendering remain separate milestones. See [the adapter probe](docs/adapter-probe.md). See [the first in-game result](docs/metal-probe.md).

## Full adapter probe

```sh
./gradlew :backend-probe:adapterProbe
./gradlew :backend-probe:suiteProbe
```

The first task initializes GLSM and the real SDL backend, redirects a controlled scene's ordinary LWJGL GL calls, and verifies all 1,024 sampled-texture pixels. The suite additionally runs the device baseline in the same isolated native runtime. These tasks open no window and run only when explicitly requested. See [host adaptation and verification scope](docs/adapter-probe.md).

## Experimental window presentation

`-Djulia.probe=window` opts into a three-frame Metal presentation experiment using PZ's existing GLFW Cocoa window. The default remains `adapter`. The window path now passes a three-frame native test with 7,934,400 verified framebuffer pixels, followed by a normal full-size menu and working input. The full build passes 35 JVM tests. Gameplay still uses its original renderer. See [the window boundary](docs/window-presentation.md).

To audit Julia's uniform compatibility bridges as well as upstream redirection, add `--compat` after `--audit-redirects`. Reports are created as new files. This remains an offline bytecode audit; it does not load game classes or enable live redirection. See [the uniform bridge checks](docs/uniform-bridges.md).

The offscreen adapter now checks three frames with GPU-idle waits to exercise command-buffer reuse. Set JVM properties `julia.probe.width` / `julia.probe.height` for a different target size; they default to 32×32. The hosted backend invalidates its state mirror at frame start so reused native command-buffer handles cannot suppress required bindings.

The [runtime preparation tool](docs/runtime-preparation.md) checks whole archives and prepares typed isolated calls plus PZ frame hooks entirely offline. It does not activate the game renderer.
