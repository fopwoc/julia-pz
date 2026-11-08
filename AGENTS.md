# Julia

## Layout

- The repository root is the PZ mod folder (`common/`, `42/mod.info`). The Gradle build lives in `java/`: shared logic
  in `java/buildSrc` (`julia.java-conventions`, Git-derived `BuildInfo`), versions in `java/gradle/libs.versions.toml`,
  shipped modules under `java/modules/`, development executables under `java/tooling/`.
- Java 25, Gradle Kotlin DSL, base package `dev.fopwoc.julia`. The `third_party/angelica/` submodule pins the `pz`
  branch of our Angelica fork, which in turn pins the `pz` branch of our GTNHLib fork at `third_party/gtnhlib`.
  `java/modules/glsm`, `sdl-gpu`, and `gtnhlib` compile their sources locally (`gtnhlib` only the files its
  `pz-sources.txt` lists). Renderer artifacts are bundled directly; do not publish them to Maven or fall back to
  published Angelica or GTNHLib binaries.
- Fork-owned renderer tests live in `third_party/angelica/renderer-tests`, a separate Gradle project included in
  Julia's build; its outputs stay in `java/modules/renderer-tests/build`. Do not mix them into upstream test trees.
  Julia keeps host, interception, isolation, window, lifecycle, logging, packaging, and integration-probe tests.
  `:renderer-tests:test` runs CPU-only regressions, including native CPU shader tools, and never creates a device/window.
- The fork owns the renderer: GL state, SDL GPU execution, shaders, textures, and the patched SDL build with its GPU
  extensions. Both forks target LWJGL3 only, with no lwjgl3ify or Minecraft classes. Julia owns the host side: agent,
  isolated loader, game and mod bytecode passes, window borrowing, startup, and the Workshop package. Fork rules (merge
  model, tracked and owned files, hook list) live in `third_party/angelica/AGENTS.md`.
- `:probe` maps to `java/tooling/probe`; its CLI/task names stay unchanged. Folders group modules but are never projects themselves. Profiling lives under `java/tooling/profiling`:
  the `:profiling-core` and `:profiling-agent` modules, the latter a third Julia entry (`profiling-agent.jar`) holding the commands, frame observer, game automation and
  `:launch`, so both sides of a comparison run one jar. `profile.sh` and `profile.ps1` only find the game and its Java; logic
  belongs in the jar. Production projects must never depend on tooling. The Workshop excludes `java/tooling`; the complete
  source archive retains it. Profiling hooks observe completed render steps, never GL behavior; CPU wall durations are not
  GPU timestamps. Launching runs only on explicit user request. `profile.sh run --dry-run` and `audit` are safe offline
  checks. Its loading-screen continuation hook must preserve the original loading checks. Keep scene control independent of
  renderer code. Batch failures stop subsequent runs. Runs get their own home folder, since ZombieBuddy keeps its preload
  list there and ignores `-cachedir`.
- Agent and probe packaging uses `buildSrc/packaging` to reduce Fastutil by static dependency closure, including nested
  classes. Compilation retains the full API. Audit new reflective Fastutil usage explicitly. `:buildSrc:test` covers
  closure behavior; run it when changing this packaging logic.
- Distribution tooling in `tools/distribution` reads committed Git objects and recursively expands only the required
  pinned fork sources. Workshop ZIPs keep Julia source and `SOURCES.md`; the separate source ZIP includes build inputs
  and generated `.source-info.properties` for Git-free builds. `BuildInfo` reads that identity only without `.git`.
  Test packaging changes with `python3 -m unittest discover -s tools/tests` and `:buildSrc:test`.
- Keep game binaries, saves, Workshop files, extracted proprietary sources, and local machine paths out of Git.

- Julia wires into the game two ways over the shared `:launch` startup, and neither jar carries the other's entry.
  `:preload` (`julia-preload.jar`) is the signed ZombieBuddy mod and the only thing the Workshop ships. `:agent`
  (`julia-agent.jar`, plus `julia.dll` on Windows) is the standalone `-javaagent` and audit mode, released on GitHub
  only; profiling launches through it.
- The preload entry `dev.fopwoc.julia.preload.PreMain` reads ZombieBuddy
  internals by reflection because its preload entry passes neither `Instrumentation` nor a code source. Its loaded
  `Loader` is redefined in place, and its woven patch classes and the Lua exposer may already be loaded.
- Preload needs `julia-preload.jar.zbs` beside the jar. Locally `:preload:signJar` writes it from the Git-ignored
  `signing/ed25519-private.der`. CI signs only in `release.yml`'s publish job, with `tools/sign-preload.sh` and the
  `ZBS_PRIVATE_KEY` secret (base64 DER) of the `release` environment, which accepts `x.y.z` tags alone; a tag ruleset
  limits creating them to maintainers. The shared `build.yml` never signs, so pull requests and branches stay
  unsigned. Never commit the key, and keep the two signers byte-identical. The signer's SteamID64 must match the
  Workshop uploader.

## Code style

- Formatting is palantir-java-format through Spotless and is part of `check`; run `./gradlew spotlessApply` rather
  than formatting by hand.
- Organize packages by responsibility. Keep one primary type per file. Use imports, not inline fully qualified names.
- Use named LWJGL/SDL constants instead of hex literals. Bridges may import GL constants freely because javac inlines
  them.
- Log with `System.Logger`, named after the declaring class. Only entry points call `LogConfiguration.configure()`.
  Default level is `error`; `LOG_LEVEL` overrides it. Keep error logs detailed and normal output compact.
  Renderer Log4j calls use the service-loaded JUL adapter in `backend.logging`; keep Log4j Core and its XML config out
  of Julia's runtime. Never configure the JVM-global JUL root logger.
- New renderer code and fixes go into fork source, never into Julia. The fork never references `dev.fopwoc.julia`;
  Julia configures it through fork APIs.
- Julia never rewrites renderer or GTNHLib bytecode. Change their behavior in fork source.
- Renderer GL routing and its endpoint names live in the fork's `glsm.redirect.lwjgl3`; Julia invokes
  `Lwjgl3Redirects` before its host/isolation adaptations. `Endpoint` adds the shared Julia targets.
- Default framebuffer resizing, initial black presentation, and GPU command teardown live in the fork's `sdlgpu.frame`.
  Julia decides when to start/present/resize/abort; it does not manipulate framebuffer or command-buffer internals.
- Julia's `backend` module is host code only: runtime, window borrowing, platform selection, and logging integration.
  Static entry points that rewritten game code calls live in the fork's `sdlgpu.bridge`; `Endpoint` names them.
- Use comments for non-obvious reasoning only. Javadoc a class when its role or invariant isn't evident from its name.
- Tests cover behavior that can regress silently. Don't test trivial mappings.

## Runtime invariants

- Only JDK types and `dev.fopwoc.julia.runtime` types may cross into the isolated runtime. Every isolating class loader
  must delegate `dev.fopwoc.julia.runtime.` to its parent side.
- Every class a rewritten call may target must be in `Endpoint`. Bytecode passes name targets as strings and never load
  backend classes.
- Every class in `dev.fopwoc.julia.probe.scene` is redirected as it loads and must contain redirected GL calls. Put
  non-GL helpers elsewhere.
- Startup loads every prepared class right after adding the transformer. A class first loaded while another agent's
  transformer runs on that thread skips all transformers, so it would silently keep native GL.
- Prepared GLFW and legacy Display framebuffer-size queries use logical window dimensions (`FramebufferSizePass`),
  matching `HostWindow.useLogicalResolution`; native presentation applies display scaling once. Keep array, buffer,
  raw-pointer, and method-reference forms consistent, including final presentation queries.
- OS specifics stay behind `Platform`, `ForeignWindow`, and `BorrowedWindow`, with implementations in per-OS packages
  such as `window.macos`. GPU-API specifics stay behind `GpuBackend` (Metal, Vulkan, Direct3D 12). Native SDL
  extensions are bound once in the fork's `sdlgpu.extension` and export the same C symbols for every GPU backend
  that implements them.
- After changing service registration, loader prefixes, package names, or endpoint strings, run
  `./gradlew :probe:suiteProbe`. Unit tests do not cover the isolated loader path.

## Safety

- Tests and offline audits must run without launching the game or opening a window. Native probes run only on request.
- Game launches require an explicit user request; use a separate test profile and preserve active sessions. The user
  performs visual and gameplay checks.
- Never install into or patch the live game as a side effect of building or auditing.

## Git

- Make small local commits after verified milestones, with short plain messages. Do not push without a request.
- Commit innermost first: GTNHLib `pz`, then Angelica `pz` with its pin bump, then Julia with its pin bump.
