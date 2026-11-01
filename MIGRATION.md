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
| `transform.load`: 13 `UpstreamPatch` passes, `UpstreamPatches`, `UpstreamPatchesTest` | Source hooks in tracked fork files, one commit per pass | Then delete the infrastructure. Keep `Anchors` only if game passes still use it |
| `backend.Upstream` (private-state reflection) | Deleted | Becomes package-private access from owned code in the same package |
| `backend.layer` chain + `HostedBackend` | `sdl-gpu`, owned subclass of `SDLGPURenderBackend` | Domain split stays; the class stays out of the hot upstream file |
| `Subsystems` | `sdl-gpu`, owned, held by the backend | `current()` keeps its owning-thread check |
| `*Hooks` (7 classes) | Folded into the owned domain class the hook calls | The fork calls them directly |
| `backend.shader` (+ `metal`, `vulkan`) | `sdl-gpu` `shader` | SPIR-V reflection, linked programs, sampler resources |
| `backend.texture`, `texture.buffer` | `sdl-gpu` `resource` / `sampler` | |
| `backend.framebuffer`, `query`, `sync`, `vertex`, `buffer` | `sdl-gpu` matching domain packages | |
| `backend.diagnostic` | `sdl-gpu` `diagnostic` | `DrawTracePass` becomes a hook |
| `backend.compat` | `CompatShaderInput` → `glsm`; `ReportedState` → `sdl-gpu`; `TessellatorState` deleted with the LWJGL2 namespace | |
| `backend.sdl` (Java bindings) | `sdl-gpu` | Together with the native code below |
| `native/sdl` (CMake patches and SDL GPU extensions) | Fork, owned, next to `sdl-gpu` | The SDL build tasks move from Julia's `backend` build to its `sdl-gpu` build. `native/agentlib` stays in Julia |
| `transform.load.LegacyNamespacePass`, glsm stubs, `Lwjgl3ify-Aware` marker, lwjgl3ify dependency | Deleted | `glsm` compiles against LWJGL3 |
| `backend.bridge` (32 entry points) | `sdl-gpu` owned `bridge` package | `Endpoint` keeps naming them by string. Simple one-to-one mappings may become `GLSMRedirector` entries |
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

### 1. LWJGL3 and host seams in the fork

Project Zomboid runs LWJGL3, so the LWJGL2 layer goes away. Compiling `glsm` against LWJGL3 instead of LWJGL2 and
lwjgl3ify gives 202 errors in 15 files. Those are symbol errors only; signature mismatches may appear once they are
fixed.

- Delete what only LWJGL2 hosts need: the GLU shapes (`compat/lwjgl/Angelica{Disk,PartialDisk,Sphere,Cylinder}`),
  `Lwjgl2GLRenderBackend`, `Tessellator` users, and the `net.minecraft` stubs.
- Port the rest to LWJGL3 in place (`GLStateManager`, `RenderBackend`, the SPIR-V and shader cache classes,
  `MainThreadPump`, and a few others): `Display`/`Drawable`/`GLContext`/`ContextCapabilities`, the LWJGL2 debug
  callbacks, and `LWJGLException`.
- Drop lwjgl3ify, `org.lwjglx`, `Lwjgl3Aware`, the `Lwjgl3ify-Aware` manifest marker, `injectLwjgl3Aware`, the
  `stubs` source set, and `LegacyNamespacePass`.

APIs Julia fills before `init()`, replacing what `SDLGPUGate` and lwjgl3ify used to provide:

- Window: `Device` takes a host-window handle and size/scale queries (replaces `WindowQueryPass`).
- Window thread: `configureWindowThread` (done).
- Remove the remaining `org.lwjglx.opengl.Display`/`Drawable` use in `sdl-gpu` behind that window API.

### 1b. Native SDL into the fork

Every file in `native/sdl` adds a GL feature SDL GPU lacks (queries, texture and buffer views, sparse targets, sampler
arrays and capacity, vertex divisors, wider uniform windows), fixes Vulkan usage and layout rules for GL's untyped
resources, or keeps SDL loadable in Steam's runtime (`xkbcommon_baseline`). None of it works around how vanilla
Angelica integrates, so it all moves as renderer code, with its Java bindings (`backend.sdl`).

While moving, check whether these four can be handled in Java instead of by patching SDL:

- `uniform_ranges`: push large default-block uniforms through a storage buffer.
- `buffer_storage`: create buffers with every usage they may take on.
- `image_layouts`: track image usage in the renderer.
- `integer_samplers`: bind a nearest sampler for integer formats.

### 2. Backend core into `sdl-gpu`

Move `Subsystems`, the layer chain, and `HostedBackend` into `sdl-gpu` as owned files. Replace each `Upstream` handle
with direct access, widening upstream members from `private` to package-private where needed. The `*Hooks` classes
stay in Julia for now and reach `Subsystems` across the module boundary, so the remaining bytecode patches keep
working.

### 3. Domains, one commit each

For each domain, move its Julia code and hooks into the fork and turn its `UpstreamPatch` into source edits at the
same anchors. Simplest and most isolated first:

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

### 4. Bridges

Move `backend.bridge` into `sdl-gpu`, update `Endpoint` strings and `TargetAbi`. Where a bridge is a plain overload
mapping, prefer a `GLSMRedirector` table entry and delete the bridge.

### 5. Julia cleanup

- Delete `UpstreamPatch`, `UpstreamPatches`, `UpstreamPatchesTest`, `Upstream`, the hook classes, and empty packages.
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
