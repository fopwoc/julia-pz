# Renderer migration into the Angelica fork

Goal: Julia's renderer work becomes source in the fork's `pz` branch (`glsm`, `sdl-gpu`) instead of bytecode patches,
reflection, and subclass layers around a pinned upstream. Julia keeps what is about Project Zomboid as a host: the agent,
the isolated loader, game and mod bytecode passes, window borrowing, startup, diagnostics UI, and the Workshop package.

The tree does not need to compile or pass tests between steps. Phase 6 restores the build, tests, and `suiteProbe`.
Delete this file when the migration is done.

## Fork model

Details live in `third_party/angelica/AGENTS.md`. In short:

- Merge upstream into `pz` periodically; never rewrite `pz` history, because Julia's history pins its commits.
- **Tracked** upstream files get small hooks only. **Owned** files are the ones `pz` added and are free to change.
  **Deleted** files are Minecraft glue removed on `pz`.
- Dependencies point one way: Julia uses the fork; the fork never names `dev.fopwoc.julia`. Julia configures the fork
  through fork APIs (window, window thread, native extensions, limits).

## Where Julia code goes

| Julia today | Destination | Notes |
| --- | --- | --- |
| `transform.load`: 13 `UpstreamPatch` passes, `UpstreamPatches`, `UpstreamPatchesTest` | Source hooks in tracked fork files | Done in phases 1 and 3; infrastructure deleted, `Anchors` kept for game passes |
| `backend.Upstream` (private-state reflection) | `sdlgpu.Upstream`, reflection-free | Done in phase 2; reads widened upstream members |
| `backend.layer` chain + `HostedBackend` | `sdlgpu.layer`, `sdlgpu.HostedBackend` | Done in phase 2 |
| `Subsystems` | `sdlgpu.Subsystems` | Done in phase 2; `current()` keeps its owning-thread check |
| `*Hooks` (7 classes) | Owned hook classes in the fork's domain packages | Done in phase 3; tracked files call them directly |
| `backend.shader` (+ `metal`, `vulkan`) | `sdl-gpu` `shader` | SPIR-V reflection, linked programs, sampler resources |
| `backend.texture`, `texture.buffer` | `sdl-gpu` `resource` / `sampler` | |
| `backend.framebuffer`, `query`, `sync`, `vertex`, `buffer` | `sdl-gpu` matching domain packages | |
| `backend.diagnostic` | `sdl-gpu` `diagnostic` | `DrawTracePass` becomes a hook |
| `backend.compat` | `CompatShaderInput` → `glsm`; `ReportedState` → `sdl-gpu`; `TessellatorState` deleted with the LWJGL2 namespace | |
| `backend.sdl` (Java bindings) | `sdl-gpu` `extension` | Four moved in 1b; the rest move with their domains |
| `native/sdl` (CMake patches and SDL GPU extensions) | `sdl-gpu/native` in the fork | Done in 1b. `native/agentlib` and the Dockerfile stay in Julia |
| `transform.load.LegacyNamespacePass`, glsm stubs, `Lwjgl3ify-Aware` marker, lwjgl3ify dependency | Deleted | `glsm` compiles against LWJGL3 |
| `backend.bridge` (32 entry points) | `sdlgpu.bridge` | Done in phases 2 to 4; `Endpoint` names them by string |
| `WindowQueryPass`, `window.HostWindow` | A host-window API on `Device`; `HostWindow` stays in Julia and implements it | |
| `backend.runtime`, `window`, `platform`, `gpu`, `utils` | Stay in Julia | `backend` shrinks to host runtime glue |
| `agent`, `host`, `render-runtime`, `transform.pass`, `prepare`, `audit`, `gl-audit`, `workshop`, `probe` | Stay in Julia | Update imports and endpoint strings |

Owned files go into upstream's domain packages (`com.gtnewhorizons.angelica.sdlgpu.<domain>`) rather than
`dev.fopwoc.julia.*`. Package-private access then replaces `Upstream` with one-word visibility edits instead of public
accessors. `git diff --diff-filter=A upstream/master...pz` lists owned files, so no package convention is needed to
find them.

## Phases

### 0. Groundwork

- Add an `upstream` remote (GTNewHorizons/Angelica) and enable `rerere` in the submodule.
- Write fork rules (`third_party/angelica/AGENTS.md`) and update Julia's `AGENTS.md`.

### 1. LWJGL3 and host seams in the fork (done)

- `glsm` and `sdl-gpu` compile against LWJGL3 with no lwjgl3ify, `org.lwjglx`, or `Lwjgl3Aware`. The LWJGL2
  `Display`/`Drawable` context model, GLU quadrics, and `Lwjgl2GLRenderBackend` are deleted; the fork's `AGENTS.md`
  lists each change.
- `Device.setWindowSource` replaces `WindowQueryPass`; Julia's `HostWindow` implements `WindowSource`.
- `configureWindowThread` covers the window thread.

Left over:

- **GTNHLib (done).** `fopwoc/GTNHLib` is a fork on `pz` (from tag `0.11.36`) nested in the Angelica fork at
  `third_party/gtnhlib`. Julia's `:gtnhlib` module compiles the 60 upstream files `pz-sources.txt` lists plus the
  fork's own `src/pz/java` (a Minecraft-free `Tessellator`, the direct-capture `TessellatorManager`, and
  `CapturingTessellator.Flags`). lwjgl3ify, `LegacyNamespacePass`, `TessellatorState`, the Minecraft stubs, and
  `GLCapabilitiesOverride` are gone. The GTNHLib fork's `AGENTS.md` lists its edits.
- **Splash.** Switched off at its entry: no splash target, no checkpoint dispatch. Its dormant code stays to keep
  merges small; GLSM's splash flags still follow Julia's `markSplashComplete`.
- **Capabilities.** `GLStateManager.capabilities` comes from LWJGL3's thread-local `GL.getCapabilities()`; GLSM must
  initialize on the thread that ran the backend's `init()`. Check it once tests are back in scope.
- **Redirector tables.** LWJGL2-only mappings (`GLU`, `Project`, `Util`, LWJGL2-signature extensions) are dead under
  LWJGL3 but harmless; prune them when the bridges move.

### 1b. Native SDL into the fork (done)

The CMake sources moved to `third_party/angelica/sdl-gpu/native`. Julia's `sdl-gpu` module builds them into
`java/modules/sdl-gpu/build/sdl` and packs the library into its jar; `NativeTarget` in `buildSrc` serves both that
and the LWJGL natives in `backend`. The Docker build takes the sources as the named context `sdl`, and the universal
build trees live in `native/build/`. `SdlExtension`, `SdlSparseTargets`, `SdlTextureViews`, `SdlVertexInputs`, and
the `Sdl` failure helper are in the fork (`sdlgpu.extension`, `sdlgpu.util`).

Left over: `SdlBufferTextures`, `SdlTimestamps`, and `SdlOcclusionQueries` implement Julia's driver interfaces and
call `Upstream`, so they move with their domains in phase 3. `SdlDeveloperHud` loads natives through Julia's
`Platform` and stays with the host.

Every file in the native build adds a GL feature SDL GPU lacks (queries, texture and buffer views, sparse targets, sampler
arrays and capacity, vertex divisors, wider uniform windows), fixes Vulkan usage and layout rules for GL's untyped
resources, or keeps SDL loadable in Steam's runtime (`xkbcommon_baseline`). None of it works around how vanilla
Angelica integrates, so it all moves as renderer code.

Not yet checked: whether these four can be handled in Java instead of by patching SDL:

- `uniform_ranges`: push large default-block uniforms through a storage buffer.
- `buffer_storage`: create buffers with every usage they may take on.
- `image_layouts`: track image usage in the renderer.
- `integer_samplers`: bind a nearest sampler for integer formats.

### 2. Backend core into `sdl-gpu` (done)

`HostedBackend`, `Subsystems`, `Upstream`, the layer chain, and everything they reach moved into `sdl-gpu`: 78
classes. Julia's `backend.<domain>` became `sdlgpu.<domain>` (`backend.sdl` became `sdlgpu.extension`). That
covers most of `shader`, `texture`, `framebuffer`, `query`, `sync`, `vertex`, and `buffer`, all of `diagnostic`,
and four bridges (`BlendBridge`, `FramebufferClearBridge`, `BufferTransferBridge`, `TextureReadbackBridge`).
`Upstream` keeps its API but reads widened upstream members instead of using reflection. `HostedBackend` is the
fork's only registered `RenderBackend`; glsm's stale `Lwjgl2GLRenderBackend` registration is gone. The six unit tests
of moved classes now sit in the fork's `sdl-gpu/src/test`, which Julia's build doesn't compile yet.

Still in Julia: the seven `*Hooks` classes and the classes only they or the passes use. They reach the fork across
the module boundary, so the remaining bytecode patches keep working.

### 3. Domains, one commit each (done)

All twelve passes are source edits in the fork now, one commit each, and every hook they called is an owned fork
class. Where glsm needed backend behavior, a no-op `RenderBackend` method that the hosted backend's layers override
stands in, because glsm cannot depend on sdl-gpu. The sampler shader bridge, `GpuBackend`, and the Metal/Vulkan
SPIR-V rewriters moved with the last pass. `UpstreamPatch`, `UpstreamPatches`, and `UpstreamPatchesTest` are deleted;
`RuntimeLoader` calls `LegacyNamespacePass` directly, and `Anchors` stays for game passes. The fork's `AGENTS.md`
lists every hook. Original order:

1. `TextureStoragePass`: `ResourceManager`
2. `DrawTracePass`: `PipelineApplier`, `ContextState`
3. `CompatShaderPass`: `GLSMPrograms`, `CompatShaderTransformer`
4. `TextureBindingPass`: `GLSMQueries`, `GLSMTextures`
5. `ColorMaskPass` and `BlendEquationPass`: `GLSMBlend`, `PipelineCache`, `GLStateManager`
6. `FramebufferPass`: `FrameManager`, `PipelineApplier`, `PipelineCache`, `FBOClearTracker`, `FboState`
7. `VertexInputPass`: `GLSMVertexInput`, `VertexWriteReplayer`, `PipelineCache`
8. `TextureViewPass`: `GLSMTextureLifetime`, `PipelineApplier`, `FBOClearTracker`, `ResourceManager`, `SamplerBinder`
9. `BufferTexturePass`: `ComputeBinder`, `ContextState`, `ResourceManager`, `SamplerBinder`
10. `SamplerBindingPass`: `ComputeBinder`, `VertexWriteReplayer`, `FBOClearTracker`, `MovedSamplerCopies`,
    `StorageTextureBinder`
11. `SamplerResourcePass`: `VertexWriteReplayer`, `ContextState`, `FBOClearTracker`, `SamplerBinder`,
    `SamplerOverflow`, `ShaderManager`, `GLStateManager` (largest, last)

Record every hook added to a tracked file in the fork's `AGENTS.md` hook list.

### 4. Bridges (done)

All 30 bridges are in `sdlgpu.bridge`, with `ResourceOutput` and `IndirectDraws` (`sdlgpu.vertex`); `Endpoint` names
them there and keeps their `upstream` flag `false`. `DebugCallbackBridge` declares its own callback `MethodType`
instead of reading Julia's `DebugCallback`. Their unit tests are in the fork's test tree.

Later, optional: a bridge that is only a plain overload mapping can become a `GLSMRedirector` table entry.

### 5. Julia cleanup

- Delete empty packages. (`UpstreamPatch*` and the hook classes are already gone; `Upstream` lives on in the fork.)
- Shrink `backend` to runtime, window, platform, GPU selection, and native loading.
- Drop Gradle dependencies and excludes the fork no longer needs.
- Switch logging to the bridge described below.

### 6. Restore

- Compile, `spotlessApply` on Julia modules, unit tests.
- Move tests that cover moved code into the fork's `src/test/java` and wire them through `julia.renderer-conventions`.
- `./gradlew :probe:suiteProbe`, then hand visual and gameplay checks to the user.

## Logging

Angelica logs through the log4j API, and Julia through `System.Logger` on JUL. Neither set of calls changes:

- Replace `log4j-core` and `log4j2.xml` with `org.apache.logging.log4j:log4j-to-jul` (same 2.26.1 release), so log4j
  calls land in JUL with Julia's format and `LOG_LEVEL`.
- Angelica names its loggers freely (`"Angelica/SDLGPU"`, `"SpirvCompiler"`) rather than by package, so JUL puts them
  directly under its root logger. Teach `LogConfiguration` those names. If that proves messy, a small log4j `Provider`
  in Julia can prefix every name with `dev.fopwoc.julia.renderer.` instead.
- Moved Julia code keeps `System.Logger`.
