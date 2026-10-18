# Runtime routing preparation

Julia now has a JDK-only `render-runtime` module between game call sites and the isolated Angelica runtime. It contains no LWJGL or native dependency. Typed invokedynamic calls retain original argument/return types. Method references use generated typed helpers. Targets are resolved in an explicitly permitted isolated loader and cached per thread-owned session; they cannot survive session closure or cross threads.

`FrameBindings` recognizes the exact static `RenderThread.lockStepRenderStep()Z` method, one `SpriteRenderer.postRender()` call, and one subsequent `Display.update(boolean)` call. It inserts begin/present hooks and an exception handler that aborts an active frame while preserving the original exception. Host display/input updates remain in place. Missing, duplicate, or already-instrumented anchors are rejected. These are offline transforms, not installed game hooks.

`RuntimePreparation` runs upstream redirection and Julia overload bridges, checks exact public static target signatures from class resources, and rejects residual native GL calls, native capability objects, unsupported cross-loader types, and the host ImGui renderer. `ArchivePlan` additionally rejects duplicate class definitions and requires exactly one frame owner. It exposes executable bytes only when the complete supplied archive set passes. Reports contain hashes and metadata; proprietary transformed bytes remain in memory.

Run the built backend-probe CLI with `--prepare-runtime new-report.tsv /path/to/game.jar [mod.jar ...]`. Report paths must be new. An activation candidate means structural readiness only.

## Verified offline

- 54 JVM tests pass, including a fresh headless JVM under `-Xverify:all`.
- Synthetic ordinary LWJGL calls, method references, and float arrays execute against graphics endpoints in a sibling loader without invoking native GL. Closed sessions reject further dispatch.
- Injected frame bytecode executes, preserves the host update call, and aborts a throwing draw without presenting it.
- Frame-state failures, suppressed cleanup exceptions, cross-thread ownership, duplicate class definitions, all-or-nothing archive gating, and defensive bytecode copies are covered.
- The existing windowless Metal suite now uses the same typed dispatch boundary against actual isolated Angelica endpoints: 2,048 raw baseline pixels plus 1,024 adapter pixels pass. The adapter still exercises three synchronously drained frames.
- Installed vanilla archive preflight: 158/182 graphics consumers structurally ready, 1,305 routed calls in ready classes, one frame anchor set, 35 unique blockers. No original game classes were loaded or written.

## Remaining integration

The startup agent remains audit-only. Automatic live activation and persistent Metal frame ownership are not installed. Existing probe entry points still own their frame lifecycle; their dispatch session intentionally rejects injected begin/present calls. Wiring a live session requires replacing the host swap behavior while retaining input, handling native capability queries and debug callbacks, and addressing remaining vanilla GL operations. The new ZombieBuddy artifact has not been tested inside PZ. No game was launched or live mod replaced during this work.

Relevant API checks: [JDK transformer behavior](https://docs.oracle.com/en/java/javase/25/docs/api/java.instrument/java/lang/instrument/ClassFileTransformer.html) (transformer exceptions do not reliably stop loading, so throwing from a transformer alone is not an activation gate), and the published Angelica 2.2.34 redirector and backend binaries.
