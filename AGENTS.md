# Julia

- Use Java 25 and Gradle Groovy DSL. Keep modules under `modules/` and organize Java packages by responsibility.
- Phase 1 is vanilla Project Zomboid on SDL-GPU/Metal. Phase 2 adds Viewpoint and other modern OpenGL mods.
- Prefer the published Angelica graphics libraries. Record exact versions, source revisions, and verified integration limitations.
- Keep game binaries, saves, Workshop files, extracted proprietary sources, and local machine paths out of Git.
- Tests and audits must run without launching the game or opening a window. The user performs visual/gameplay checks.
- Do not install into or patch the live game as a side effect of building or auditing.
- Keep error logs detailed, normal output compact, and default logging at error. Use `LOG_LEVEL` for overrides.
- Make small local commits after verified milestones. Use short plain messages. Do not push without a request.
