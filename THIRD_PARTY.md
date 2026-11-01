# Third-party notices

Julia's own code is WTFNMFPL (see `COPYRIGHT`). The libraries below ship with it and keep their own licenses.

| Library | Version | License | Why it is bundled |
| --- | --- | --- | --- |
| [SDL](https://github.com/libsdl-org/SDL) | 3.4.12 | zlib | GPU runtime, rebuilt as `SDL3Julia` with Julia's native extensions |
| [Angelica fork](https://github.com/fopwoc/Angelica) `glsm`, `sdl-gpu` | `third_party/angelica/` Git revision (`pz` over upstream `master`) | LGPL v3 | GL state manager and SDL3 GPU backend that Julia renders through |
| [LWJGL3ify](https://github.com/GTNewHorizons/lwjgl3ify) | 3.0.31 | LGPL v3 | Legacy LWJGL namespace and capability API that Angelica expects from its host |

Julia builds SDL 3.4.12 with the native extensions in `native/sdl/`: patches shared by every GPU API in `common/`, and
per-API extensions such as `metal/` (timestamps, occlusion queries, buffer textures, texture views, vertex inputs,
indirect sampler tables and sparse color targets).
The CMake source archive is pinned by SHA-256; the original SDL source remains under its zlib license
(`licenses/SDL.txt`). The extensions and SDL ownership/cycling changes are clearly marked as Julia code and are not upstream SDL APIs.

Other runtime dependencies (LWJGL, ASM, Guava, Log4j, …) carry their notices inside their own JARs.

## LGPL v3 compliance

- Angelica's `glsm` and `sdl-gpu` are compiled from the pinned `third_party/angelica/` Git submodule using Julia's build descriptors.
  Other LGPL dependencies remain published artifacts. They ship as separate JARs under `lib/` in the agent and
  probe distribution, so you can drop in an interface-compatible replacement. Compile-only Minecraft signatures
  from Angelica are not included in the renderer JARs.
- `java/buildSrc/src/main/kotlin/Lwjgl3AwareInjection.kt` is copied from Angelica's build tooling and remains under
  the upstream LGPL v3 terms. It reproduces the SDL backend's existing build-time LWJGL3 annotation stamping.
- Julia adapts some library classes **in memory as they load**: it remaps legacy LWJGL package names following
  LWJGL3ify's `@Lwjgl3Aware` rule, and applies version-pinned patches to Angelica, each listed in
  `java/modules/transform/.../load/UpstreamPatches.java`. The files on disk are never changed.
- The corresponding renderer source is the exact Git revision recorded by the `third_party/angelica/` submodule, available
  in [fopwoc/Angelica](https://github.com/fopwoc/Angelica), together with the build descriptors under `java/modules/glsm`
  and `java/modules/sdl-gpu` in this repository. `:glsm:sourcesJar` and `:sdl-gpu:sourcesJar` produce local source archives.
  Angelica's original [license](third_party/angelica/LICENSE) and per-file upstream notices remain in force; compiled renderer
  JARs also carry that license under `META-INF/licenses/angelica/`.
- The full GPL v3 and LGPL v3 texts are in `licenses/` and ship with every distribution.

`TessellatorState` only reproduces the fields that GTNHLib's tessellator inherits. It contains no Minecraft code and
throws on any rendering call.
