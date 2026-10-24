# Default-block GL uniforms routinely exceed SDL's 4 KiB descriptor window.
set(backend "${sdl_patched}/src/gpu/vulkan/SDL_gpu_vulkan.c")
file(READ "${backend}" source)
julia_replace("#define MAX_UBO_SECTION_SIZE          4096     // 4   KiB"
    "#define MAX_UBO_SECTION_SIZE          65536    // 64 KiB, bounded by the device limit below" 1)
# Keep the original eight-section pool capacity when expanding each descriptor window. Every dynamic
# offset must leave a full descriptor range inside the allocation, even for a small uniform upload.
julia_replace("            UNIFORM_BUFFER_SIZE);"
    "            (MAX_UBO_SECTION_SIZE * 8));" 2)
julia_replace("bufferInfos[bufferInfoCount].range = MAX_UBO_SECTION_SIZE;"
    "bufferInfos[bufferInfoCount].range = SDL_min(MAX_UBO_SECTION_SIZE, renderer->physicalDeviceProperties.properties.limits.maxUniformBufferRange);" 3)
julia_replace([=[    Uint32 blockSize =
        VULKAN_INTERNAL_NextHighestAlignment32(
            length,
            commandBuffer->renderer->minUBOAlignment);]=] [=[    Uint32 limit = SDL_min(MAX_UBO_SECTION_SIZE,
        commandBuffer->renderer->physicalDeviceProperties.properties.limits.maxUniformBufferRange);
    if (length > limit) {
        SDL_LogCritical(SDL_LOG_CATEGORY_GPU,
            "Uniform upload exceeds Vulkan descriptor range: %u bytes, limit %u", length, limit);
        abort();
    }
    Uint32 blockSize =
        VULKAN_INTERNAL_NextHighestAlignment32(
            length,
            commandBuffer->renderer->minUBOAlignment);]=] 1)
file(WRITE "${backend}" "${source}")
