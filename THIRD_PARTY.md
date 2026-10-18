# Third-party graphics libraries

Julia's graphics backend consumes the unmodified Angelica `sdl-gpu` and `glsm` 2.2.34 artifacts. No Angelica implementation source is vendored in Julia. Julia's isolated runtime remaps selected legacy binding and host-state references in memory as classes load; the published JARs remain intact on disk.

Angelica is developed by GTNewHorizons and its contributors and is licensed under GNU LGPL version 3. Its upstream license also preserves credits for daxnitro, id_miner, and karyonix. Additional upstream file-specific notices remain applicable.

- Project: https://github.com/GTNewHorizons/Angelica
- License and credits: https://github.com/GTNewHorizons/Angelica/blob/master/LICENSE
- SDL-GPU source artifact: https://nexus.gtnewhorizons.com/repository/releases/com/gtnewhorizons/angelica/sdl-gpu/2.2.34/sdl-gpu-2.2.34-sources.jar
- GLSM source artifact: https://nexus.gtnewhorizons.com/repository/releases/com/gtnewhorizons/angelica/glsm/2.2.34/glsm-2.2.34-sources.jar

Copies of LGPL v3 and GPL v3 are in `licenses/`. Both the `probe` distribution and the mod's `Julia.jar` keep dependencies as separate JAR files under `lib/`, so interface-compatible library replacements can be supplied there.

The isolated runtime also includes Mojang's unmodified LaunchWrapper 1.12 solely because Angelica's shader debug-environment check references `Launch.blackboard`. Julia does not invoke the Minecraft launcher. Source: https://github.com/Mojang/LegacyLauncher

Other dependencies retain their own licenses and notices in their published artifacts. This notice describes dependency licensing; it does not assign Julia's original source a project license.

The isolated runtime also supplies unmodified LWJGL3ify 3.0.31 and RetroFuturaBootstrap 1.1.0 as host compatibility libraries. Both projects use LGPL v3; their sources and notices are available upstream:

- https://github.com/GTNewHorizons/lwjgl3ify
- https://github.com/GTNewHorizons/RetroFuturaBootstrap

Julia's host adaptation follows LWJGL3ify's documented namespace and `@Lwjgl3Aware` rules. Its tessellator state class implements only a host-side inherited API shape, with explicit failures for unsupported base rendering operations.
