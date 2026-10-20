# Third-party notices

Julia's own code is WTFNMFPL (see `COPYRIGHT`). The libraries below ship with it and keep their own licenses.

| Library | Version | License | Why it is bundled |
| --- | --- | --- | --- |
| [SDL](https://github.com/libsdl-org/SDL) | 3.4.12 | zlib | GPU runtime, rebuilt as `SDL3Julia` with Julia's native extensions |
| [Angelica](https://github.com/GTNewHorizons/Angelica) `glsm`, `sdl-gpu` | 2.2.34 | LGPL v3 | GL state manager and SDL3 GPU backend that Julia renders through |
| [LWJGL3ify](https://github.com/GTNewHorizons/lwjgl3ify) | 3.0.31 | LGPL v3 | Legacy LWJGL namespace and capability API that Angelica expects from its host |
| [RetroFuturaBootstrap](https://github.com/GTNewHorizons/RetroFuturaBootstrap) | 1.1.0 | LGPL v3 | Runtime classes Angelica expects from its host |
| [LaunchWrapper](https://github.com/Mojang/LegacyLauncher) | 1.12 | Mojang | Angelica's shader debug check reads `Launch.blackboard`; Julia never starts the launcher |

Julia builds SDL 3.4.12 with the native extensions in `native/sdl/`: patches shared by every GPU API in `common/`, and
per-API extensions such as `metal/` (timestamps, occlusion queries, buffer textures, texture views, vertex inputs,
indirect sampler tables and sparse color targets).
The CMake source archive is pinned by SHA-256; the original SDL source remains under its zlib license
(`licenses/SDL.txt`). The extensions and SDL ownership/cycling changes are clearly marked as Julia code and are not upstream SDL APIs.

Other runtime dependencies (LWJGL, ASM, Guava, Log4j, …) carry their notices inside their own JARs.

## LGPL v3 compliance

- LGPL libraries remain unmodified published artifacts, shipped as separate JARs under `lib/` in the agent and
  probe distribution, so you can drop in an interface-compatible replacement.
- Julia adapts some library classes **in memory as they load**: it remaps legacy LWJGL package names following
  LWJGL3ify's `@Lwjgl3Aware` rule, and applies version-pinned patches to Angelica, each listed in
  `java/modules/transform/.../load/UpstreamPatches.java`. The files on disk are never changed.
- Angelica's sources are published next to its binaries:
  [glsm](https://nexus.gtnewhorizons.com/repository/releases/com/gtnewhorizons/angelica/glsm/2.2.34/glsm-2.2.34-sources.jar),
  [sdl-gpu](https://nexus.gtnewhorizons.com/repository/releases/com/gtnewhorizons/angelica/sdl-gpu/2.2.34/sdl-gpu-2.2.34-sources.jar).
  Its [license](https://github.com/GTNewHorizons/Angelica/blob/master/LICENSE) also credits daxnitro, id_miner, and
  karyonix, and per-file upstream notices still apply.
- The full GPL v3 and LGPL v3 texts are in `licenses/` and ship with every distribution.

`TessellatorState` only reproduces the fields that GTNHLib's tessellator inherits. It contains no Minecraft code and
throws on any rendering call.
