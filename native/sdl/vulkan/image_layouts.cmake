# GL images can later become sampled textures; mixed image/sampler usage shares GENERAL layout.
set(backend "${sdl_patched}/src/gpu/vulkan/SDL_gpu_vulkan.c")
file(READ "${backend}" source)
julia_replace([=[    texture = VULKAN_INTERNAL_CreateTexture(
        renderer,
        createinfo);]=] [=[    SDL_GPUTextureCreateInfo juliaInfo = *createinfo;
    juliaInfo.usage |= SDL_GPU_TEXTUREUSAGE_SAMPLER;
    createinfo = &juliaInfo;
    texture = VULKAN_INTERNAL_CreateTexture(
        renderer,
        createinfo);]=] 1)
julia_replace([=[memoryBarrier.oldLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;]=] [=[memoryBarrier.oldLayout = JULIA_VulkanSamplerImageLayout(texture);]=] 1)
julia_replace([=[memoryBarrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;]=] [=[memoryBarrier.newLayout = JULIA_VulkanSamplerImageLayout(texture);]=] 1)
julia_replace([=[typedef enum VulkanUniformBufferStage]=] [=[static VkImageLayout JULIA_VulkanSamplerImageLayout(const VulkanTexture *texture)
{
    return texture->usage & (SDL_GPU_TEXTUREUSAGE_GRAPHICS_STORAGE_READ | SDL_GPU_TEXTUREUSAGE_COMPUTE_STORAGE_READ |
        SDL_GPU_TEXTUREUSAGE_COMPUTE_STORAGE_WRITE | SDL_GPU_TEXTUREUSAGE_COMPUTE_STORAGE_SIMULTANEOUS_READ_WRITE)
        ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
}

typedef enum VulkanUniformBufferStage]=] 1)
# Shader reads from the default sampled state may run in any GL shader stage.
julia_replace("srcStages = VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;"
    "srcStages = VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;" 3)
julia_replace("dstStages = VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;"
    "dstStages = VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;" 3)
foreach(stage vertex fragment compute)
    julia_replace("    VkImageView ${stage}SamplerTextureViewBindings[MAX_TEXTURE_SAMPLERS_PER_STAGE];"
        "    VkImageView ${stage}SamplerTextureViewBindings[MAX_TEXTURE_SAMPLERS_PER_STAGE];\n    VkImageLayout julia${stage}ImageLayouts[MAX_TEXTURE_SAMPLERS_PER_STAGE];" 1)
    julia_replace("            vulkanCommandBuffer->${stage}SamplerTextureViewBindings[firstSlot + i] = textureContainer->activeTexture->fullView;"
        "            vulkanCommandBuffer->${stage}SamplerTextureViewBindings[firstSlot + i] = textureContainer->activeTexture->fullView;\n            vulkanCommandBuffer->julia${stage}ImageLayouts[firstSlot + i] = JULIA_VulkanSamplerImageLayout(textureContainer->activeTexture);" 1)
    julia_replace("imageInfos[imageInfoCount].imageView = commandBuffer->${stage}SamplerTextureViewBindings[i];\n            imageInfos[imageInfoCount].imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;"
        "imageInfos[imageInfoCount].imageView = commandBuffer->${stage}SamplerTextureViewBindings[i];\n            imageInfos[imageInfoCount].imageLayout = commandBuffer->julia${stage}ImageLayouts[i];" 1)
endforeach()
file(WRITE "${backend}" "${source}")
