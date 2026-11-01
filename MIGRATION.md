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
- **CPU verification:** fork-owned regressions live in `third_party/angelica/renderer-tests`, a separate project in
  Julia's build. Its 27 test classes pass 66 tests; Julia's retained host and integration unit tests pass 166 tests.
  Upstream test trees are not wired into this suite. All build outputs remain under `java/modules/renderer-tests/build`.
- **Not verified:** `suiteProbe` has not run since phase 1, there has been no game run, and CI has not yet run the
  nested-submodule checkout or the Docker `sdl` build context. Each former bytecode patch was converted
  by hand, so a subtle behavior difference is possible; the fork's `AGENTS.md` hook list maps each one.

## Remaining work

### 5. Julia cleanup — complete

- Log4j API calls now use `log4j-to-jul` 2.26.1. Julia's service-loaded `RendererLoggingProvider` selects a context
  factory that delegates to the standard JUL adapter with names under `dev.fopwoc.julia.renderer.`. Upstream logging
  calls stay unchanged; `log4j-core` and its XML configuration are removed.
- `LogConfiguration` configures Julia, Angelica, and GTNHLib's JUL namespaces at `LOG_LEVEL`, without changing the
  JVM's global root logger. It keeps strong references to those namespace roots and remains idempotent across loaders.
- The host backend no longer repeats renderer dependencies or exclusions owned by `glsm` and `gtnhlib`. Obsolete
  Minecraft/launcher repository filters are removed; nested renderer initialization has an explicit error message.
- `Endpoint` no longer stores the unused `upstream` flag; GLSM membership and the shared JDK-only boundary stay explicit.
- Verified compilation of main and test sources offline, the host logging provider's JAR resources, and the cleaned
  runtime dependency graph. CPU and native regression results are recorded below.

### 6. Restore

- **Done:** collected our 17 tests previously placed in `sdl-gpu/src/test` and the 10 renderer tests still in Julia
  into the fork's separate `renderer-tests` project. Package names follow the renderer classes. Upstream tests remain
  in their original trees and are not mixed into this suite.
- **Done:** `./gradlew :renderer-tests:test --offline` and Julia's `./gradlew test --offline --no-build-cache` pass
  (66 renderer tests plus 166 Julia tests, no skipped tests). Renderer tests use CPU shader tools and memory natives,
  never a device/window. Agent packaging contains no renderer-tests artifact.
- **Done:** six fresh-JVM logging regressions cover debug/info/warn/error, the unset default, and invalid `LOG_LEVEL`.
  Each valid case uses two real `RuntimeLoader` instances and checks the isolated provider, arbitrary Log4j names,
  library and owned `System.Logger` output, parameter formatting, fatal/error messages and exceptions, and stable
  namespace handlers. The JVM-global root logger and unrelated game loggers retain their configuration.
- **GPU restore fixes:** capabilities are installed before GLSM pre-initialization, including offscreen startup.
  GTNHLib's direct-buffer factory uses LWJGL's supported wrapper instead of a Java 8 private constructor. Layered
  framebuffer attachments reach the slice implementation; invalid named attachments preserve framebuffer bindings.
  Indexed enable/disable validation lives at the backend boundary so direct GLSM calls cannot bypass it.
- **Metal uniform fix:** SDL's 32 KiB pool could not hold 48 KiB uploads. Metal now uses a larger pool and rejects
  uploads beyond the 64 KiB block limit before copying. The existing large-uniform and rollover checks pass.
- **GPU status:** the complete windowless suite passes on local Metal / Apple M4 Max, including the raw device and
  isolated LWJGL-to-GLSM path. Linux/Vulkan and visual gameplay are not validated by this run.
- **CPU status:** all 233 tests pass with zero failures or skips; the complete `check` task passes.
- Native probe (only when the user asks, it opens no window): `./gradlew :probe:suiteProbe`. It covers the isolated
  loader path that unit tests don't, which changed in every phase.
- Check `GLStateManager.capabilities`: it comes from LWJGL3's thread-local `GL.getCapabilities()`, so GLSM must
  initialize on the thread that ran the backend's `onPostWindowCreate()`.
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
