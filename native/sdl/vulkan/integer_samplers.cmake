# Integer texelFetch does not filter in GL; Vulkan still requires a compatible sampler descriptor.
set(backend "${sdl_patched}/src/gpu/vulkan/SDL_gpu_vulkan.c")
file(READ "${backend}" source)
julia_replace([=[typedef struct VulkanSampler]=] [=[static bool JULIA_VulkanIntegerFormat(SDL_GPUTextureFormat format)
{
    switch (format) {
    case SDL_GPU_TEXTUREFORMAT_R32_UINT: case SDL_GPU_TEXTUREFORMAT_R32G32_UINT:
    case SDL_GPU_TEXTUREFORMAT_R32G32B32A32_UINT: case SDL_GPU_TEXTUREFORMAT_R32_INT:
    case SDL_GPU_TEXTUREFORMAT_R32G32_INT: case SDL_GPU_TEXTUREFORMAT_R32G32B32A32_INT:
        return true;
    default: return IsIntegerFormat(format);
    }
}

typedef struct VulkanSampler]=] 1)
julia_replace([=[typedef struct VulkanSampler
{]=] [=[typedef struct VulkanSampler
{
    VkSampler juliaIntegerSampler;]=] 1)
julia_replace([=[    SDL_SetAtomicInt(&vulkanSampler->referenceCount, 0);]=] [=[    vkSamplerCreateInfo.magFilter = VK_FILTER_NEAREST;
    vkSamplerCreateInfo.minFilter = VK_FILTER_NEAREST;
    vkSamplerCreateInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    vkSamplerCreateInfo.anisotropyEnable = VK_FALSE;
    vkSamplerCreateInfo.borderColor = VK_BORDER_COLOR_INT_TRANSPARENT_BLACK;
    vulkanResult = renderer->vkCreateSampler(renderer->logicalDevice, &vkSamplerCreateInfo, NULL, &vulkanSampler->juliaIntegerSampler);
    if (vulkanResult != VK_SUCCESS) {
        renderer->vkDestroySampler(renderer->logicalDevice, vulkanSampler->sampler, NULL);
        SDL_free(vulkanSampler);
        CHECK_VULKAN_ERROR_AND_RETURN(vulkanResult, vkCreateSampler, NULL);
    }
    SDL_SetAtomicInt(&vulkanSampler->referenceCount, 0);]=] 1)
julia_replace([=[    SDL_free(vulkanSampler);
}]=] [=[    renderer->vkDestroySampler(renderer->logicalDevice, vulkanSampler->juliaIntegerSampler, NULL);
    SDL_free(vulkanSampler);
}]=] 1)
foreach(stage vertex fragment compute)
    julia_replace("        if (vulkanCommandBuffer->${stage}SamplerBindings[firstSlot + i] != sampler->sampler) {"
        "        VkSampler juliaSampler = JULIA_VulkanIntegerFormat(textureContainer->header.info.format) ? sampler->juliaIntegerSampler : sampler->sampler;\n        if (vulkanCommandBuffer->${stage}SamplerBindings[firstSlot + i] != juliaSampler) {" 1)
    julia_replace("            vulkanCommandBuffer->${stage}SamplerBindings[firstSlot + i] = sampler->sampler;"
        "            vulkanCommandBuffer->${stage}SamplerBindings[firstSlot + i] = juliaSampler;" 1)
endforeach()
file(WRITE "${backend}" "${source}")
