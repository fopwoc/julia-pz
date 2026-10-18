# Julia

- Use Java 25 and Gradle Kotlin DSL. The repository root is the PZ mod folder (`common/`, `42/`); the Gradle build lives in `java/`. Shared build logic lives in `java/buildSrc` (`julia.java-conventions`, Git-derived `BuildInfo`); versions live in `java/gradle/libs.versions.toml`. Keep modules under `java/modules/` and organize Java packages by responsibility.
- Phase 1 is vanilla Project Zomboid on SDL-GPU/Metal. Phase 2 adds Viewpoint and other modern OpenGL mods.
- Prefer the published Angelica graphics libraries. Record exact versions, source revisions, and verified integration limitations.
- Keep game binaries, saves, Workshop files, extracted proprietary sources, and local machine paths out of Git.
- Tests and offline audits must run without launching the game or opening a window. Game launches require an explicit user request; use a separate test profile and preserve active sessions. The user performs visual/gameplay checks.
- Do not install into or patch the live game as a side effect of building or auditing.
- Keep error logs detailed, normal output compact, and default logging at error. Use `LOG_LEVEL` for overrides.
- Make small local commits after verified milestones. Use short plain messages. Do not push without a request.
