# Version-pinned SDL extension: absent Metal color attachments retain their output slots.
function(julia_sparse_replace before after expected)
    string(LENGTH "${source}" original_length)
    string(REPLACE "${before}" "" removed "${source}")
    string(LENGTH "${removed}" removed_length)
    string(LENGTH "${before}" anchor_length)
    math(EXPR count "(${original_length} - ${removed_length}) / ${anchor_length}")
    if(NOT count EQUAL expected)
        message(FATAL_ERROR "Pinned sparse-target anchor changed: ${before}; count=${count}")
    endif()
    string(REPLACE "${before}" "${after}" source "${source}")
    set(source "${source}" PARENT_SCOPE)
endfunction()

set(gpu_api "${sdl_patched}/src/gpu/SDL_gpu.c")
file(READ "${gpu_api}" source)
string(FIND "${source}" "Julia sparse color formats" api_patched)
if(api_patched EQUAL -1)
    julia_sparse_replace([=[        for (Uint32 i = 0; i < graphicsPipelineCreateInfo->target_info.num_color_targets; i += 1) {
            CHECK_TEXTUREFORMAT_ENUM_INVALID]=] [=[        for (Uint32 i = 0; i < graphicsPipelineCreateInfo->target_info.num_color_targets; i += 1) {
            /* Julia sparse color formats: only the Metal extension permits an absent slot. */
            if (graphicsPipelineCreateInfo->target_info.color_target_descriptions[i].format == SDL_GPU_TEXTUREFORMAT_INVALID &&
                SDL_strcmp(SDL_GetGPUDeviceDriver(device), "metal") == 0) continue;
            CHECK_TEXTUREFORMAT_ENUM_INVALID]=] 1)
    julia_sparse_replace([=[            TextureCommonHeader *textureHeader = (TextureCommonHeader *)color_target_infos[i].texture;]=] [=[            if (color_target_infos[i].texture == NULL &&
                SDL_strcmp(SDL_GetGPUDeviceDriver(COMMAND_BUFFER_DEVICE), "metal") == 0) continue;
            TextureCommonHeader *textureHeader = (TextureCommonHeader *)color_target_infos[i].texture;]=] 1)
    file(WRITE "${gpu_api}" "${source}")
endif()

file(READ "${backend}" source)
string(FIND "${source}" "gpu_sparse_targets.m" metal_patched)
if(metal_patched EQUAL -1)
    julia_sparse_replace([=[            MetalTextureContainer *container = (MetalTextureContainer *)colorTargetInfos[i].texture;]=] [=[            if (colorTargetInfos[i].texture == NULL) continue;
            MetalTextureContainer *container = (MetalTextureContainer *)colorTargetInfos[i].texture;]=] 2)
    julia_sparse_replace([=[        for (Uint32 i = 0; i < createinfo->target_info.num_color_targets; i += 1) {
            blendState =]=] [=[        for (Uint32 i = 0; i < createinfo->target_info.num_color_targets; i += 1) {
            if (createinfo->target_info.color_target_descriptions[i].format == SDL_GPU_TEXTUREFORMAT_INVALID) continue;
            blendState =]=] 1)
    string(APPEND source "\n" [=[#include "gpu_sparse_targets.m"]=] "\n")
    file(WRITE "${backend}" "${source}")
endif()
