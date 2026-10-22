# GL drivers honour sampler-array indices that vary per primitive; Vulkan needs descriptor indexing for NonUniform.
set(backend "${sdl_patched}/src/gpu/vulkan/SDL_gpu_vulkan.c")
file(READ "${backend}" source)
julia_replace([=[    bool juliaVertexAccessBeyondStride;]=] [=[    bool juliaVertexAccessBeyondStride;
    bool juliaNonUniformSamplers;]=] 1)
julia_replace([=[static Uint32 JULIA_VulkanInitVertexInput(VulkanRenderer *, VkPhysicalDeviceVertexAttributeDivisorFeaturesEXT *);]=] [=[static Uint32 JULIA_VulkanInitVertexInput(VulkanRenderer *, VkPhysicalDeviceVertexAttributeDivisorFeaturesEXT *);
static Uint32 JULIA_VulkanInitNonUniform(VulkanRenderer *, VkPhysicalDeviceDescriptorIndexingFeaturesEXT *, const char **);
static bool JULIA_VulkanCheckNonUniform(VulkanRenderer *, const SDL_GPUShaderCreateInfo *);]=] 1)
julia_replace([=[    Uint32 juliaVertexExtensionCount = JULIA_VulkanInitVertexInput(renderer, &juliaVertexFeatures);]=] [=[    Uint32 juliaVertexExtensionCount = JULIA_VulkanInitVertexInput(renderer, &juliaVertexFeatures);
    VkPhysicalDeviceDescriptorIndexingFeaturesEXT juliaIndexingFeatures;
    const char *juliaIndexingExtensions[2];
    Uint32 juliaIndexingExtensionCount = JULIA_VulkanInitNonUniform(renderer, &juliaIndexingFeatures, juliaIndexingExtensions);]=] 1)
julia_replace([=[        &renderer->supports) + juliaVertexExtensionCount;]=] [=[        &renderer->supports) + juliaVertexExtensionCount + juliaIndexingExtensionCount;]=] 1)
julia_replace([=[    if (juliaVertexExtensionCount) deviceExtensions[deviceCreateInfo.enabledExtensionCount - 1] = VK_EXT_VERTEX_ATTRIBUTE_DIVISOR_EXTENSION_NAME;]=] [=[    if (juliaVertexExtensionCount) deviceExtensions[GetDeviceExtensionCount(&renderer->supports)] = VK_EXT_VERTEX_ATTRIBUTE_DIVISOR_EXTENSION_NAME;
    for (Uint32 i = 0; i < juliaIndexingExtensionCount; i++)
        deviceExtensions[GetDeviceExtensionCount(&renderer->supports) + juliaVertexExtensionCount + i] = juliaIndexingExtensions[i];]=] 1)
julia_replace([=[    if (juliaVertexExtensionCount) {
        juliaVertexFeatures.pNext = (void *)deviceCreateInfo.pNext;]=] [=[    if (juliaIndexingExtensionCount) {
        juliaIndexingFeatures.pNext = (void *)deviceCreateInfo.pNext;
        deviceCreateInfo.pNext = &juliaIndexingFeatures;
    }
    if (juliaVertexExtensionCount) {
        juliaVertexFeatures.pNext = (void *)deviceCreateInfo.pNext;]=] 1)
julia_replace([=[    if (!JULIA_VulkanReadSamplerWidths(renderer, createinfo, vulkanShader->juliaSamplerWidths) ||]=] [=[    if (!JULIA_VulkanCheckNonUniform(renderer, createinfo) ||
        !JULIA_VulkanReadSamplerWidths(renderer, createinfo, vulkanShader->juliaSamplerWidths) ||]=] 1)
file(WRITE "${backend}" "${source}\n#include \"gpu_nonuniform_samplers.c\"\n")
