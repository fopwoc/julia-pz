# Third-party graphics libraries

Julia's backend probe consumes the unmodified Angelica `sdl-gpu` and `glsm` 2.2.34 artifacts. No Angelica implementation source is vendored in Julia.

Angelica is developed by GTNewHorizons and its contributors and is licensed under GNU LGPL version 3. Its upstream license also preserves credits for daxnitro, id_miner, and karyonix. Additional upstream file-specific notices remain applicable.

- Project: https://github.com/GTNewHorizons/Angelica
- License and credits: https://github.com/GTNewHorizons/Angelica/blob/master/LICENSE
- SDL-GPU source artifact: https://nexus.gtnewhorizons.com/repository/releases/com/gtnewhorizons/angelica/sdl-gpu/2.2.34/sdl-gpu-2.2.34-sources.jar
- GLSM source artifact: https://nexus.gtnewhorizons.com/repository/releases/com/gtnewhorizons/angelica/glsm/2.2.34/glsm-2.2.34-sources.jar

Copies of LGPL v3 and GPL v3 are in `licenses/`. The probe's Unix distribution keeps dependencies as separate JAR files under `lib/`, so interface-compatible library replacements can be supplied there.

Other dependencies retain their own licenses and notices in their published artifacts. This notice describes dependency licensing; it does not assign Julia's original source a project license.
