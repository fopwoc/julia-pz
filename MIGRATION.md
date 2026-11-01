# Renderer migration into the forks

Goal: Julia's renderer work lives as source in its forks instead of bytecode patches, reflection, and subclass layers
around pinned upstream binaries. Julia keeps what is about Project Zomboid as a host: the agent, the isolated loader,
game and mod bytecode passes, window borrowing, startup, diagnostics UI, and the Workshop package.

The tree does not need to pass tests between steps; phase 6 restores tests and `suiteProbe`. Keep it compiling
(`./gradlew compileJava compileTestJava -x :sdl-gpu:processResources --offline`). Delete this file when done.

## Where things stand

- **Forks.** `third_party/angelica` pins `fopwoc/Angelica` (`pz`), which pins `fopwoc/GTNHLib` (`pz`, from tag
  `0.11.36`) at `third_party/gtnhlib`. Rules, zones, and every edit to an upstream file are in each fork's
  `AGENTS.md`; read both before touching fork code. Commit innermost first and bump each pin in its own commit.
- **Renderer.** `glsm` and `sdl-gpu` are LWJGL3 only, with no lwjgl3ify, `org.lwjglx`, or Minecraft classes. The
  hosted backend (`sdlgpu.HostedBackend`, layers in `sdlgpu.layer`, `Subsystems`, reflection-free `Upstream`), all
  bridges (`sdlgpu.bridge`), the former bytecode patches (now source hooks plus owned hook classes), `GpuBackend`, and
  the Metal/Vulkan SPIR-V rewriters live in `sdl-gpu`. `HostedBackend` is the only registered `RenderBackend`.
- **Native SDL.** The CMake patch set lives in `third_party/angelica/sdl-gpu/native`; Julia's `:sdl-gpu` module builds
  it into `java/modules/sdl-gpu/build/sdl` and packs it. Docker takes it as the named build context `sdl`; universal
  builds go to `native/build/`.
- **GTNHLib.** Julia's `:gtnhlib` module compiles the 60 upstream files the fork's `pz-sources.txt` lists plus its
  `src/pz/java` (a Minecraft-free `Tessellator`, a direct-capture-only `TessellatorManager`,
  `CapturingTessellator.Flags`).
- **Julia `backend`** is host code only: `runtime`, `window` (per-OS borrowing, `HostWindow` implements the fork's
  `WindowSource`), `platform`, and `sdl.SdlDeveloperHud`. Julia rewrites no renderer or library bytecode anymore.
- **Not verified:** unit tests and `suiteProbe` have not run since phase 1, there has been no game run, and CI has not
  yet run the nested-submodule checkout or the Docker `sdl` build context. Each former bytecode patch was converted
  by hand, so a subtle behavior difference is possible; the fork's `AGENTS.md` hook list maps each one.

## Remaining work

### 5. Julia cleanup

- **Logging.** Angelica logs through log4j and Julia through `System.Logger` on JUL; don't rewrite calls. Replace
  `log4j-core` and `java/modules/backend/src/main/resources/log4j2.xml` with
  `org.apache.logging.log4j:log4j-to-jul` (2.26.1, matching the catalog's log4j). Angelica's logger names are free
  strings (`"Angelica/SDLGPU"`, `"SpirvCompiler"`), so they land under JUL's root; teach `LogConfiguration`
  (`java/modules/utils/.../logging`) to configure them, or add a small log4j `Provider` in Julia that prefixes every
  name with `dev.fopwoc.julia.renderer.`.
- **Gradle.** `java/modules/backend/build.gradle.kts` still excludes jcpp's build-only dependencies and lists
  `runtimeOnly` libraries "Angelica expects from its host"; check which are still needed now that GTNHLib is a
  project dependency. `java/settings.gradle.kts` still declares repository groups for lwjgl3ify, GTNHLib, and
  RetroFuturaBootstrap that nothing may resolve anymore.
- **Endpoint flags.** Bridges keep `upstream = false` in `Endpoint`; decide whether that flag still means anything now
  that they live in the fork, and simplify if not.

### 6. Restore

- Wire the fork test trees (`third_party/angelica/glsm/src/test`, `sdl-gpu/src/test`) into
  `julia.renderer-conventions`. They mix upstream tests, some needing a real device (`*GpuTest`, `RealDeviceExtension`),
  with the unit tests moved from Julia. Start with the moved ones and the pure-logic upstream ones.
- Julia's own tests: `./gradlew test --offline --no-build-cache`.
- Native probe (only when the user asks, it opens no window): `./gradlew :probe:suiteProbe`. It covers the isolated
  loader path that unit tests don't, which changed in every phase.
- Check `GLStateManager.capabilities`: it comes from LWJGL3's thread-local `GL.getCapabilities()`, so GLSM must
  initialize on the thread that ran the backend's `init()`.
- Then hand visual and gameplay checks to the user.

### Optional, later

- **Dormant splash path.** `SDLGPURenderBackend` creates no splash target anymore, but `SplashDispatcher`,
  `OffscreenTarget` splash use, and the splash branches remain to keep merges small. GLSM's splash flags still follow
  Julia's `markSplashComplete`.
- **Redirector tables.** LWJGL2-only mappings in `GLSMRedirector` (`GLU`, `Project`, `Util`, LWJGL2-signature
  extensions) are dead under LWJGL3 but harmless. A bridge that only maps one GL overload to another could become a
  redirector table entry.
- **Native patches in Java?** Not yet checked whether `uniform_ranges` (storage buffer for large uniforms),
  `buffer_storage` (allocate with every usage), `image_layouts` (track usage in the renderer), or `integer_samplers`
  (nearest sampler for integer formats) could replace SDL patches.
- **Fork CI.** Both forks still have upstream's GitHub Actions enabled, which build the Minecraft mods on push and will
  likely fail. Disabling Actions in each fork's settings is the user's call.
