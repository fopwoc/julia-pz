# Julia

## Layout

- The repository root is the PZ mod folder (`common/`, `42/mod.info`). The Gradle build lives in `java/`: shared logic
  in `java/buildSrc` (`julia.java-conventions`, Git-derived `BuildInfo`), versions in `java/gradle/libs.versions.toml`,
  modules under `java/modules/`.
- Java 25, Gradle Kotlin DSL, base package `dev.fopwoc.julia`. Prefer the published Angelica artifacts over vendored
  source.
- Keep game binaries, saves, Workshop files, extracted proprietary sources, and local machine paths out of Git.

## Code style

- Formatting is palantir-java-format through Spotless and is part of `check`; run `./gradlew spotlessApply` rather
  than formatting by hand.
- Organize packages by responsibility. Keep one primary type per file. Use imports, not inline fully qualified names.
- Use named LWJGL/SDL constants instead of hex literals. Bridges may import GL constants freely because javac inlines
  them.
- Log with `System.Logger`, named after the declaring class. Only entry points call `LogConfiguration.configure()`.
  Default level is `error`; `LOG_LEVEL` overrides it. Keep error logs detailed and normal output compact.
- Use comments for non-obvious reasoning only. Javadoc a class when its role or invariant isn't evident from its name.
- Tests cover behavior that can regress silently. Don't test trivial mappings.

## Runtime invariants

- Only JDK types and `dev.fopwoc.julia.runtime` types may cross into the isolated runtime. Every isolating class loader
  must delegate `dev.fopwoc.julia.runtime.` to its parent side.
- Every class a rewritten call may target must be in `Endpoint`. Bytecode passes name targets as strings and never load
  backend classes.
- Every class in `dev.fopwoc.julia.probe.scene` is redirected as it loads and must contain redirected GL calls. Put
  non-GL helpers elsewhere.
- Platform specifics stay behind `Platform`, `ForeignWindow`, and `BorrowedWindow`, with implementations in
  per-platform packages such as `window.macos`.
- After changing service registration, loader prefixes, package names, or endpoint strings, run
  `./gradlew :probe:suiteProbe`. Unit tests do not cover the isolated loader path.

## Safety

- Tests and offline audits must run without launching the game or opening a window. Native probes run only on request.
- Game launches require an explicit user request; use a separate test profile and preserve active sessions. The user
  performs visual and gameplay checks.
- Never install into or patch the live game as a side effect of building or auditing.

## Git

- Make small local commits after verified milestones, with short plain messages. Do not push without a request.
