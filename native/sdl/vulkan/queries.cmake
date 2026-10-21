set(backend "${sdl_patched}/src/gpu/vulkan/SDL_gpu_vulkan.c")
file(READ "${backend}" source)
julia_replace([=[    CommandBufferCommonHeader common;]=] [=[    CommandBufferCommonHeader common;
    void *juliaQueryPoints;
    void *juliaVisibilityPoint;]=] 1)
julia_replace([=[    commandBuffer = SDL_malloc(sizeof(VulkanCommandBuffer));]=] [=[    commandBuffer = SDL_malloc(sizeof(VulkanCommandBuffer));
    commandBuffer->juliaQueryPoints = NULL;
    commandBuffer->juliaVisibilityPoint = NULL;]=] 1)
julia_replace([=[} VulkanCommandBuffer;]=] [=[} VulkanCommandBuffer;
static void JULIA_VulkanDestroyQueries(VulkanRenderer *);
static void JULIA_VulkanQueriesSubmitted(VulkanCommandBuffer *);
static void JULIA_VulkanQueriesCleaned(VulkanCommandBuffer *, bool);
static void JULIA_VulkanVisibilityPrepare(VulkanCommandBuffer *);
static void JULIA_VulkanVisibilityBegin(VulkanCommandBuffer *);
static void JULIA_VulkanVisibilityEnd(VulkanCommandBuffer *);]=] 1)
julia_replace([=[    bool supportsMultiDrawIndirect;]=] [=[    bool supportsMultiDrawIndirect;
    bool juliaPreciseOcclusion;
    void *juliaVisibilityActive;
    void *juliaTimestampPool;
    void *juliaOcclusionPool;]=] 1)
julia_replace([=[    // creating the logical device]=] [=[    renderer->juliaPreciseOcclusion = haveDeviceFeatures.occlusionQueryPrecise;
    features->desiredVulkan10DeviceFeatures.occlusionQueryPrecise = haveDeviceFeatures.occlusionQueryPrecise;
    // creating the logical device]=] 1)
julia_replace([=[    // Present, if applicable]=] [=[    JULIA_VulkanQueriesSubmitted(vulkanCommandBuffer);
    // Present, if applicable]=] 1)
julia_replace([=[    bool cancel)
{
    if (commandBuffer->autoReleaseFence)]=] [=[    bool cancel)
{
    JULIA_VulkanQueriesCleaned(commandBuffer, cancel);
    if (commandBuffer->autoReleaseFence)]=] 1)
julia_replace([=[    renderer->vkCmdBeginRenderPass(]=] [=[    JULIA_VulkanVisibilityPrepare(vulkanCommandBuffer);
    renderer->vkCmdBeginRenderPass(]=] 1)
julia_replace([=[        VK_SUBPASS_CONTENTS_INLINE);]=] [=[        VK_SUBPASS_CONTENTS_INLINE);
    JULIA_VulkanVisibilityBegin(vulkanCommandBuffer);]=] 1)
julia_replace([=[    renderer->vkCmdEndRenderPass(]=] [=[    JULIA_VulkanVisibilityEnd(vulkanCommandBuffer);
    renderer->vkCmdEndRenderPass(]=] 1)
julia_replace([=[    renderer->vkDestroyDevice(renderer->logicalDevice, NULL);]=] [=[    JULIA_VulkanDestroyQueries(renderer);
    renderer->vkDestroyDevice(renderer->logicalDevice, NULL);]=] 1)
file(WRITE "${backend}" "${source}\n#include \"gpu_queries.c\"\n")
set(functions "${sdl_patched}/src/gpu/vulkan/SDL_gpu_vulkan_vkfuncs.h")
file(READ "${functions}" source)
julia_replace([=[VULKAN_DEVICE_FUNCTION(vkGetFenceStatus)]=] [=[VULKAN_DEVICE_FUNCTION(vkGetFenceStatus)
VULKAN_DEVICE_FUNCTION(vkCreateQueryPool)
VULKAN_DEVICE_FUNCTION(vkDestroyQueryPool)
VULKAN_DEVICE_FUNCTION(vkGetQueryPoolResults)
VULKAN_DEVICE_FUNCTION(vkCmdResetQueryPool)
VULKAN_DEVICE_FUNCTION(vkCmdWriteTimestamp)
VULKAN_DEVICE_FUNCTION(vkCmdBeginQuery)
VULKAN_DEVICE_FUNCTION(vkCmdEndQuery)]=] 1)
file(WRITE "${functions}" "${source}")
