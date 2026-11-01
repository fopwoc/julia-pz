# Julia

## Layout

- The repository root is the PZ mod folder (`common/`, `42/mod.info`). The Gradle build lives in `java/`: shared logic
  in `java/buildSrc` (`julia.java-conventions`, Git-derived `BuildInfo`), versions in `java/gradle/libs.versions.toml`,
  modules under `java/modules/`.
- Java 25, Gradle Kotlin DSL, base package `dev.fopwoc.julia`. The `third_party/angelica/` submodule pins the `pz`
  branch of our Angelica fork; `java/modules/glsm` and `sdl-gpu` compile its source locally. Renderer artifacts are
  bundled directly; do not publish them to Maven or fall back to published Angelica binaries.
- The fork owns the renderer: GL state, SDL GPU execution, shaders, textures, and the patched SDL build with its GPU
  extensions. The fork targets LWJGL3 only. GTNHLib, still linked as a binary,
  is the last LWJGL2 code: `LegacyNamespacePass` remaps only it, and lwjgl3ify stays on the runtime classpath for it. Julia owns the host side: agent,
  isolated loader, game and mod bytecode passes, window borrowing, startup, and the Workshop package. Fork rules (merge
  model, tracked and owned files, hook list) live in `third_party/angelica/AGENTS.md`.
- Migration in progress: Julia's renderer code is moving into the fork per `MIGRATION.md`. Until it finishes, the tree
  may not compile and tests may fail; don't restore either unless a step asks for it.
- Keep game binaries, saves, Workshop files, extracted proprietary sources, and local machine paths out of Git.

## Code style

- Formatting is palantir-java-format through Spotless and is part of `check`; run `./gradlew spotlessApply` rather
  than formatting by hand.
- Organize packages by responsibility. Keep one primary type per file. Use imports, not inline fully qualified names.
- Use named LWJGL/SDL constants instead of hex literals. Bridges may import GL constants freely because javac inlines
  them.
- Log with `System.Logger`, named after the declaring class. Only entry points call `LogConfiguration.configure()`.
  Default level is `error`; `LOG_LEVEL` overrides it. Keep error logs detailed and normal output compact.
- New renderer code and fixes go into fork source, never into Julia. The fork never references `dev.fopwoc.julia`;
  Julia configures it through fork APIs.
- Julia no longer rewrites renderer bytecode; the only load-time remap left is `LegacyNamespacePass` for GTNHLib.
  Change renderer behavior in fork source, never by patching renderer classes from Julia.
- Bridges hold static entry points that rewritten game code calls; layers change how GL work runs on SDL GPU. Keep the
  two apart, wherever they live.
- Use comments for non-obvious reasoning only. Javadoc a class when its role or invariant isn't evident from its name.
- Tests cover behavior that can regress silently. Don't test trivial mappings.

## Runtime invariants

- Only JDK types and `dev.fopwoc.julia.runtime` types may cross into the isolated runtime. Every isolating class loader
  must delegate `dev.fopwoc.julia.runtime.` to its parent side.
- Every class a rewritten call may target must be in `Endpoint`. Bytecode passes name targets as strings and never load
  backend classes.
- Every class in `dev.fopwoc.julia.probe.scene` is redirected as it loads and must contain redirected GL calls. Put
  non-GL helpers elsewhere.
- OS specifics stay behind `Platform`, `ForeignWindow`, and `BorrowedWindow`, with implementations in per-OS packages
  such as `window.macos`. GPU-API specifics stay behind `GpuBackend` (Metal, Vulkan, Direct3D 12). Native SDL
  extensions are bound once (in the fork's `sdlgpu.extension`; the bindings still tied to Julia types stay in
  `backend.sdl` until their domains move) and export the same C symbols for every GPU
  backend that implements them.
- After changing service registration, loader prefixes, package names, or endpoint strings, run
  `./gradlew :probe:suiteProbe`. Unit tests do not cover the isolated loader path.

## Safety

- Tests and offline audits must run without launching the game or opening a window. Native probes run only on request.
- Game launches require an explicit user request; use a separate test profile and preserve active sessions. The user
  performs visual and gameplay checks.
- Never install into or patch the live game as a side effect of building or auditing.

## Git

- Make small local commits after verified milestones, with short plain messages. Do not push without a request.
- Commit fork changes on `pz` first, then bump the submodule pin in Julia in its own commit.
