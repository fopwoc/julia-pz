# Metal render passes and pipelines skip absent color slots instead of binding dummy textures.
file(READ "${backend}" source)
string(FIND "${source}" "gpu_sparse_targets.m" metal_patched)
if(metal_patched EQUAL -1)
    julia_replace([=[            MetalTextureContainer *container = (MetalTextureContainer *)colorTargetInfos[i].texture;]=] [=[            if (colorTargetInfos[i].texture == NULL) continue;
            MetalTextureContainer *container = (MetalTextureContainer *)colorTargetInfos[i].texture;]=] 2)
    julia_replace([=[        for (Uint32 i = 0; i < createinfo->target_info.num_color_targets; i += 1) {
            blendState =]=] [=[        for (Uint32 i = 0; i < createinfo->target_info.num_color_targets; i += 1) {
            if (createinfo->target_info.color_target_descriptions[i].format == SDL_GPU_TEXTUREFORMAT_INVALID) continue;
            blendState =]=] 1)
    string(APPEND source "\n" [=[#include "gpu_sparse_targets.m"]=] "\n")
    file(WRITE "${backend}" "${source}")
endif()
