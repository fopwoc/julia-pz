# Divisors extend SDL's pipeline properties without changing SDL's reserved step-rate field.
set(backend "${sdl_patched}/src/gpu/vulkan/SDL_gpu_vulkan.c")
file(READ "${backend}" source)
julia_replace([=[typedef struct VulkanRenderer VulkanRenderer;]=] [=[typedef struct VulkanRenderer VulkanRenderer;
static Uint32 JULIA_VulkanInitVertexInput(VulkanRenderer *, VkPhysicalDeviceVertexAttributeDivisorFeaturesEXT *);
static bool JULIA_VulkanValidateVertexInput(VulkanRenderer *, const SDL_GPUGraphicsPipelineCreateInfo *);
static void JULIA_VulkanApplyVertexInput(const SDL_GPUGraphicsPipelineCreateInfo *, VkPipelineVertexInputStateCreateInfo *, VkVertexInputBindingDescription *, VkPipelineVertexInputDivisorStateCreateInfoEXT *, VkVertexInputBindingDivisorDescriptionEXT *);]=] 1)
julia_replace([=[    bool supportsMultiDrawIndirect;]=] [=[    bool supportsMultiDrawIndirect;
    Uint32 juliaMaxVertexDivisor;
    bool juliaZeroVertexDivisor;
    bool juliaVertexAccessBeyondStride;]=] 1)
julia_replace([=[    VkPhysicalDevicePortabilitySubsetFeaturesKHR portabilityFeatures;]=] [=[    VkPhysicalDevicePortabilitySubsetFeaturesKHR portabilityFeatures;
    VkPhysicalDeviceVertexAttributeDivisorFeaturesEXT juliaVertexFeatures;
    Uint32 juliaVertexExtensionCount = JULIA_VulkanInitVertexInput(renderer, &juliaVertexFeatures);]=] 1)
julia_replace([=[deviceCreateInfo.enabledExtensionCount = GetDeviceExtensionCount(
        &renderer->supports);]=] [=[deviceCreateInfo.enabledExtensionCount = GetDeviceExtensionCount(
        &renderer->supports) + juliaVertexExtensionCount;]=] 1)
julia_replace([=[    CreateDeviceExtensionArray(&renderer->supports, deviceExtensions);]=] [=[    CreateDeviceExtensionArray(&renderer->supports, deviceExtensions);
    if (juliaVertexExtensionCount) deviceExtensions[deviceCreateInfo.enabledExtensionCount - 1] = VK_EXT_VERTEX_ATTRIBUTE_DIVISOR_EXTENSION_NAME;]=] 1)
julia_replace([=[    vulkanResult = renderer->vkCreateDevice(]=] [=[    if (juliaVertexExtensionCount) {
        juliaVertexFeatures.pNext = (void *)deviceCreateInfo.pNext;
        deviceCreateInfo.pNext = &juliaVertexFeatures;
    }
    vulkanResult = renderer->vkCreateDevice(]=] 1)
julia_replace([=[        portabilityFeatures.vertexAttributeAccessBeyondStride = VK_FALSE;]=] [=[        portabilityFeatures.vertexAttributeAccessBeyondStride = renderer->juliaVertexAccessBeyondStride;]=] 1)
julia_replace([=[    VulkanGraphicsPipeline *graphicsPipeline = (VulkanGraphicsPipeline *)SDL_malloc(sizeof(VulkanGraphicsPipeline));]=] [=[    if (!JULIA_VulkanValidateVertexInput((VulkanRenderer *)driverData, createinfo)) return NULL;
    VkPipelineVertexInputDivisorStateCreateInfoEXT juliaDivisorState;
    VkVertexInputBindingDivisorDescriptionEXT juliaDivisors[MAX_VERTEX_BUFFERS];
    VulkanGraphicsPipeline *graphicsPipeline = (VulkanGraphicsPipeline *)SDL_malloc(sizeof(VulkanGraphicsPipeline));]=] 1)
julia_replace([=[    vertexInputStateCreateInfo.pVertexAttributeDescriptions = vertexInputAttributeDescriptions;]=] [=[    vertexInputStateCreateInfo.pVertexAttributeDescriptions = vertexInputAttributeDescriptions;
    JULIA_VulkanApplyVertexInput(createinfo, &vertexInputStateCreateInfo, vertexInputBindingDescriptions, &juliaDivisorState, juliaDivisors);]=] 1)
file(WRITE "${backend}" "${source}\n#include \"gpu_vertex_input.c\"\n#include \"gpu_extensions.c\"\n")
