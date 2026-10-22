#ifdef SDL_GPU_VULKAN
/* Compiled inside SDL_gpu_vulkan.c. Enables only per-fragment sampled-image array indexing, nothing else of
 * descriptor indexing, and keeps SDL's Vulkan 1.0 instance by using the extension instead of 1.2 core features. */
static bool JULIA_VulkanHasDeviceExtension(VulkanRenderer *renderer, const char *name)
{
    Uint32 count = 0;
    if (renderer->vkEnumerateDeviceExtensionProperties(renderer->physicalDevice, NULL, &count, NULL) != VK_SUCCESS) return false;
    VkExtensionProperties *extensions = SDL_calloc(count, sizeof(*extensions));
    if (!extensions) return false;
    bool found = false;
    if (renderer->vkEnumerateDeviceExtensionProperties(renderer->physicalDevice, NULL, &count, extensions) == VK_SUCCESS)
        for (Uint32 i = 0; i < count && !found; i++) found = SDL_strcmp(extensions[i].extensionName, name) == 0;
    SDL_free(extensions);
    return found;
}

static Uint32 JULIA_VulkanInitNonUniform(VulkanRenderer *renderer, VkPhysicalDeviceDescriptorIndexingFeaturesEXT *features, const char **names)
{
    SDL_zero(*features);
    features->sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DESCRIPTOR_INDEXING_FEATURES_EXT;
    renderer->juliaNonUniformSamplers = false;
    PFN_vkGetPhysicalDeviceFeatures2KHR getFeatures = (PFN_vkGetPhysicalDeviceFeatures2KHR)
        vkGetInstanceProcAddr(renderer->instance, "vkGetPhysicalDeviceFeatures2KHR");
    if (!getFeatures || !renderer->supportsPhysicalDeviceProperties2) return 0;
    if (!JULIA_VulkanHasDeviceExtension(renderer, VK_EXT_DESCRIPTOR_INDEXING_EXTENSION_NAME) ||
        !JULIA_VulkanHasDeviceExtension(renderer, VK_KHR_MAINTENANCE_3_EXTENSION_NAME)) return 0;
    VkPhysicalDeviceFeatures2 query = { 0 };
    query.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    query.pNext = features;
    getFeatures(renderer->physicalDevice, &query);
    bool supported = features->shaderSampledImageArrayNonUniformIndexing;
    SDL_zero(*features);
    features->sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DESCRIPTOR_INDEXING_FEATURES_EXT;
    if (!supported) return 0;
    features->shaderSampledImageArrayNonUniformIndexing = VK_TRUE;
    renderer->juliaNonUniformSamplers = true;
    names[0] = VK_EXT_DESCRIPTOR_INDEXING_EXTENSION_NAME;
    names[1] = VK_KHR_MAINTENANCE_3_EXTENSION_NAME;
    return 2;
}

static bool JULIA_VulkanCheckNonUniform(VulkanRenderer *renderer, const SDL_GPUShaderCreateInfo *info)
{
    if (SDL_GetBooleanProperty(info->props, "julia.gpu.requires_nonuniform_samplers", false) && !renderer->juliaNonUniformSamplers)
        return SDL_SetError("Vulkan device lacks shaderSampledImageArrayNonUniformIndexing for a per-primitive sampler-array index");
    return true;
}
#endif
