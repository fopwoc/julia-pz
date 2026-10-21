# Pinned SDL API validation: an absent color slot is allowed only where a backend extension supports it.
set(gpu_api "${sdl_patched}/src/gpu/SDL_gpu.c")
file(READ "${gpu_api}" source)
string(FIND "${source}" "Julia sparse color formats" api_patched)
if(api_patched EQUAL -1)
    julia_replace([=[        for (Uint32 i = 0; i < graphicsPipelineCreateInfo->target_info.num_color_targets; i += 1) {
            CHECK_TEXTUREFORMAT_ENUM_INVALID]=] [=[        for (Uint32 i = 0; i < graphicsPipelineCreateInfo->target_info.num_color_targets; i += 1) {
            /* Julia sparse color formats: the Julia extensions permits an absent slot. */
            if (graphicsPipelineCreateInfo->target_info.color_target_descriptions[i].format == SDL_GPU_TEXTUREFORMAT_INVALID &&
                (SDL_strcmp(SDL_GetGPUDeviceDriver(device), "metal") == 0 || SDL_strcmp(SDL_GetGPUDeviceDriver(device), "vulkan") == 0)) continue;
            CHECK_TEXTUREFORMAT_ENUM_INVALID]=] 1)
    julia_replace([=[            TextureCommonHeader *textureHeader = (TextureCommonHeader *)color_target_infos[i].texture;]=] [=[            if (color_target_infos[i].texture == NULL &&
                (SDL_strcmp(SDL_GetGPUDeviceDriver(COMMAND_BUFFER_DEVICE), "metal") == 0 || SDL_strcmp(SDL_GetGPUDeviceDriver(COMMAND_BUFFER_DEVICE), "vulkan") == 0)) continue;
            TextureCommonHeader *textureHeader = (TextureCommonHeader *)color_target_infos[i].texture;]=] 1)
    file(WRITE "${gpu_api}" "${source}")
endif()
