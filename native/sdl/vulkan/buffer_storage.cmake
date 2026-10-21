# GL storage is untyped: an indirect/vertex buffer may subsequently be bound as an SSBO.
set(backend "${sdl_patched}/src/gpu/vulkan/SDL_gpu_vulkan.c")
file(READ "${backend}" source)
julia_replace([=[    if (type == VULKAN_BUFFER_TYPE_UNIFORM) {
        vulkanUsageFlags |= VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;]=] [=[    if (type == VULKAN_BUFFER_TYPE_GPU) vulkanUsageFlags |= VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT;
    if (type == VULKAN_BUFFER_TYPE_UNIFORM) {
        vulkanUsageFlags |= VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;]=] 1)
# GL buffer targets may change after allocation; default reads include later texel fetches.
julia_replace([=[    renderer->vkCmdPipelineBarrier(
        commandBuffer->commandBuffer,
        srcStages,
        dstStages,
        0,
        0,
        NULL,
        1,
        &memoryBarrier,]=] [=[    if (buffer->type == VULKAN_BUFFER_TYPE_GPU) {
        srcStages |= VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        dstStages |= VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        memoryBarrier.srcAccessMask |= VK_ACCESS_SHADER_READ_BIT;
        memoryBarrier.dstAccessMask |= VK_ACCESS_SHADER_READ_BIT;
    }
    renderer->vkCmdPipelineBarrier(
        commandBuffer->commandBuffer,
        srcStages,
        dstStages,
        0,
        0,
        NULL,
        1,
        &memoryBarrier,]=] 1)
file(WRITE "${backend}" "${source}")
