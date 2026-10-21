#ifdef SDL_GPU_VULKAN
#define JULIA_VULKAN_QUERY_SLOTS 4096
typedef struct JuliaVulkanQueryPool {
    VkQueryPool handle;
    SDL_Mutex *lock;
    bool used[JULIA_VULKAN_QUERY_SLOTS];
} JuliaVulkanQueryPool;

/* A result belongs both to its GL query and to the recorded command until completion/cancellation. */
typedef struct JuliaVulkanQueryPoint {
    VulkanRenderer *renderer;
    JuliaVulkanQueryPool *pool;
    Uint32 slot;
    VulkanFenceHandle *fence;
    SDL_AtomicInt references;
    bool cancelled;
    double period;
    struct JuliaVulkanQueryPoint *commandNext;
    struct JuliaVulkanQueryPoint *queryNext;
} JuliaVulkanQueryPoint;

typedef struct JuliaVulkanClock {
    VulkanRenderer *renderer;
    double period;
} JuliaVulkanClock;

typedef struct JuliaVulkanVisibility {
    VulkanRenderer *renderer;
    JuliaVulkanQueryPoint *points;
    bool ended;
    bool failed;
} JuliaVulkanVisibility;

static void JULIA_VulkanReleaseQueryPoint(JuliaVulkanQueryPoint *point)
{
    if (!point || !SDL_AtomicDecRef(&point->references)) return;
    if (point->fence) VULKAN_ReleaseFence((SDL_GPURenderer *)point->renderer, (SDL_GPUFence *)point->fence);
    SDL_LockMutex(point->pool->lock);
    point->pool->used[point->slot] = false;
    SDL_UnlockMutex(point->pool->lock);
    SDL_free(point);
}

static JuliaVulkanQueryPoint *JULIA_VulkanNewQueryPoint(VulkanCommandBuffer *command, VkQueryType type)
{
    JuliaVulkanQueryPoint *point = SDL_calloc(1, sizeof(*point));
    if (!point) { SDL_OutOfMemory(); return NULL; }
    point->renderer = command->renderer;
    JuliaVulkanQueryPool **cached = (JuliaVulkanQueryPool **)(type == VK_QUERY_TYPE_TIMESTAMP
        ? &point->renderer->juliaTimestampPool : &point->renderer->juliaOcclusionPool);
    if (!*cached) {
        JuliaVulkanQueryPool *pool = SDL_calloc(1, sizeof(*pool));
        if (!pool) { SDL_free(point); SDL_OutOfMemory(); return NULL; }
        pool->lock = SDL_CreateMutex();
        VkQueryPoolCreateInfo info = {0};
        info.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
        info.queryType = type;
        info.queryCount = JULIA_VULKAN_QUERY_SLOTS;
        VkResult result = point->renderer->vkCreateQueryPool(point->renderer->logicalDevice, &info, NULL, &pool->handle);
        if (!pool->lock || result != VK_SUCCESS) {
            if (pool->handle) point->renderer->vkDestroyQueryPool(point->renderer->logicalDevice, pool->handle, NULL);
            SDL_DestroyMutex(pool->lock); SDL_free(pool); SDL_free(point);
            SDL_SetError("Cannot create Vulkan query pool: %d", result); return NULL;
        }
        *cached = pool;
    }
    point->pool = *cached;
    SDL_LockMutex(point->pool->lock);
    for (point->slot = 0; point->slot < JULIA_VULKAN_QUERY_SLOTS; point->slot++)
        if (!point->pool->used[point->slot]) break;
    if (point->slot == JULIA_VULKAN_QUERY_SLOTS) {
        SDL_UnlockMutex(point->pool->lock); SDL_free(point);
        SDL_SetError("4096 Vulkan queries are still pending; query pool exhausted"); return NULL;
    }
    point->pool->used[point->slot] = true;
    SDL_UnlockMutex(point->pool->lock);
    SDL_SetAtomicInt(&point->references, 2);
    point->commandNext = (JuliaVulkanQueryPoint *)command->juliaQueryPoints;
    command->juliaQueryPoints = point;
    point->renderer->vkCmdResetQueryPool(command->commandBuffer, point->pool->handle, point->slot, 1);
    return point;
}

static void JULIA_VulkanQueriesSubmitted(VulkanCommandBuffer *command)
{
    for (JuliaVulkanQueryPoint *point = command->juliaQueryPoints; point; point = point->commandNext) {
        point->fence = command->inFlightFence;
        (void)SDL_AtomicIncRef(&point->fence->referenceCount);
    }
}

static void JULIA_VulkanQueriesCleaned(VulkanCommandBuffer *command, bool cancelled)
{
    JuliaVulkanQueryPoint *point = command->juliaQueryPoints;
    command->juliaQueryPoints = NULL;
    command->juliaVisibilityPoint = NULL;
    while (point) {
        JuliaVulkanQueryPoint *next = point->commandNext;
        point->cancelled = cancelled;
        JULIA_VulkanReleaseQueryPoint(point);
        point = next;
    }
}

static int JULIA_VulkanReadQueryPoint(JuliaVulkanQueryPoint *point, int wait, Uint64 *value)
{
    if (!point || !value || point->cancelled) { SDL_SetError("Invalid or cancelled Vulkan query"); return -1; }
    // Never read a recycled pool's old availability before this command has actually run.
    if (!point->fence) return 0;
    VulkanRenderer *renderer = point->renderer;
    VkResult result = wait ? renderer->vkWaitForFences(renderer->logicalDevice, 1, &point->fence->fence, VK_TRUE, UINT64_MAX)
                           : renderer->vkGetFenceStatus(renderer->logicalDevice, point->fence->fence);
    if (result == VK_NOT_READY || result == VK_TIMEOUT) return 0;
    if (result != VK_SUCCESS) { SDL_SetError("Vulkan query submission failed: %d", result); return -1; }
    result = renderer->vkGetQueryPoolResults(renderer->logicalDevice, point->pool->handle, point->slot, 1,
        sizeof(*value), value, sizeof(*value), VK_QUERY_RESULT_64_BIT);
    if (result == VK_NOT_READY) return 0;
    if (result != VK_SUCCESS) { SDL_SetError("Cannot resolve Vulkan query: %d", result); return -1; }
    return 1;
}

static void JULIA_VulkanDestroyQueries(VulkanRenderer *renderer)
{
    JuliaVulkanQueryPool *pools[] = {renderer->juliaTimestampPool, renderer->juliaOcclusionPool};
    for (Uint32 i = 0; i < 2; i++) if (pools[i]) {
        renderer->vkDestroyQueryPool(renderer->logicalDevice, pools[i]->handle, NULL);
        SDL_DestroyMutex(pools[i]->lock);
        SDL_free(pools[i]);
    }
}

static int JULIA_VulkanTimestampABI(void) { return 1; }
static void *JULIA_VulkanCreateTimestampClock(SDL_GPUDevice *device)
{
    VulkanRenderer *renderer = (VulkanRenderer *)device->driverData;
    Uint32 count = 0;
    renderer->vkGetPhysicalDeviceQueueFamilyProperties(renderer->physicalDevice, &count, NULL);
    VkQueueFamilyProperties *families = SDL_calloc(count, sizeof(*families));
    if (!families) { SDL_OutOfMemory(); return NULL; }
    renderer->vkGetPhysicalDeviceQueueFamilyProperties(renderer->physicalDevice, &count, families);
    bool supported = renderer->queueFamilyIndex < count && families[renderer->queueFamilyIndex].timestampValidBits != 0;
    SDL_free(families);
    if (!supported) { SDL_SetError("Vulkan graphics queue has no timestamp counter"); return NULL; }
    JuliaVulkanClock *clock = SDL_malloc(sizeof(*clock));
    if (!clock) { SDL_OutOfMemory(); return NULL; }
    clock->renderer = renderer;
    clock->period = renderer->physicalDeviceProperties.properties.limits.timestampPeriod;
    return clock;
}
static void *JULIA_VulkanRecordTimestamp(void *handle, SDL_GPUCommandBuffer *buffer)
{
    JuliaVulkanClock *clock = handle;
    VulkanCommandBuffer *command = (VulkanCommandBuffer *)buffer;
    if (!clock || !command || clock->renderer != command->renderer) { SDL_SetError("Timestamp device mismatch"); return NULL; }
    JuliaVulkanQueryPoint *point = JULIA_VulkanNewQueryPoint(command, VK_QUERY_TYPE_TIMESTAMP);
    if (!point) return NULL;
    point->period = clock->period;
    command->renderer->vkCmdWriteTimestamp(command->commandBuffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, point->pool->handle, point->slot);
    return point;
}
static int JULIA_VulkanReadTimestamp(void *handle, int wait, Uint64 *nanoseconds)
{
    JuliaVulkanQueryPoint *point = handle;
    Uint64 ticks;
    int status = JULIA_VulkanReadQueryPoint(point, wait, &ticks);
    if (status != 1) return status;
    long double value = (long double)ticks * point->period;
    if (value < 0 || value >= 9223372036854775808.0L) { SDL_SetError("Vulkan timestamp conversion overflow"); return -1; }
    *nanoseconds = (Uint64)value;
    return 1;
}
static void JULIA_VulkanReleaseTimestamp(void *point) { JULIA_VulkanReleaseQueryPoint(point); }
static void JULIA_VulkanReleaseTimestampClock(void *clock) { SDL_free(clock); }

static int JULIA_VulkanVisibilityABI(void) { return 1; }
static void *JULIA_VulkanBeginVisibilityQuery(SDL_GPUDevice *device)
{
    VulkanRenderer *renderer = (VulkanRenderer *)device->driverData;
    if (!renderer->juliaPreciseOcclusion || renderer->juliaVisibilityActive) {
        SDL_SetError("Precise Vulkan occlusion queries unavailable or already active"); return NULL;
    }
    JuliaVulkanVisibility *query = SDL_calloc(1, sizeof(*query));
    if (!query) { SDL_OutOfMemory(); return NULL; }
    query->renderer = renderer;
    renderer->juliaVisibilityActive = query;
    return query;
}
static int JULIA_VulkanEndVisibilityQuery(void *handle)
{
    JuliaVulkanVisibility *query = handle;
    if (!query || query->renderer->juliaVisibilityActive != query) { SDL_SetError("Visibility query is not active"); return -1; }
    query->renderer->juliaVisibilityActive = NULL;
    query->ended = true;
    return 0;
}
static void JULIA_VulkanVisibilityPrepare(VulkanCommandBuffer *command)
{
    JuliaVulkanVisibility *query = command->renderer->juliaVisibilityActive;
    if (!query) return;
    JuliaVulkanQueryPoint *point = JULIA_VulkanNewQueryPoint(command, VK_QUERY_TYPE_OCCLUSION);
    if (!point) { query->failed = true; return; }
    point->queryNext = query->points;
    query->points = point;
    command->juliaVisibilityPoint = point;
}
static void JULIA_VulkanVisibilityBegin(VulkanCommandBuffer *command)
{
    JuliaVulkanQueryPoint *point = command->juliaVisibilityPoint;
    if (point) command->renderer->vkCmdBeginQuery(command->commandBuffer, point->pool->handle, point->slot, VK_QUERY_CONTROL_PRECISE_BIT);
}
static void JULIA_VulkanVisibilityEnd(VulkanCommandBuffer *command)
{
    JuliaVulkanQueryPoint *point = command->juliaVisibilityPoint;
    if (point) command->renderer->vkCmdEndQuery(command->commandBuffer, point->pool->handle, point->slot);
    command->juliaVisibilityPoint = NULL;
}
static int JULIA_VulkanReadVisibilityQuery(void *handle, int wait, Uint64 *samples)
{
    JuliaVulkanVisibility *query = handle;
    if (!query || !samples || query->failed) { SDL_SetError("Invalid or failed Vulkan visibility query"); return -1; }
    if (!query->ended) return 0;
    Uint64 sum = 0;
    for (JuliaVulkanQueryPoint *point = query->points; point; point = point->queryNext) {
        Uint64 value;
        int status = JULIA_VulkanReadQueryPoint(point, wait, &value);
        if (status != 1) return status;
        if (value > SDL_MAX_UINT64 - sum) { SDL_SetError("Vulkan sample count overflow"); return -1; }
        sum += value;
    }
    *samples = sum;
    return 1;
}
static void JULIA_VulkanReleaseVisibilityQuery(void *handle)
{
    JuliaVulkanVisibility *query = handle;
    if (!query) return;
    if (query->renderer->juliaVisibilityActive == query) query->renderer->juliaVisibilityActive = NULL;
    JuliaVulkanQueryPoint *point = query->points;
    while (point) {
        JuliaVulkanQueryPoint *next = point->queryNext;
        JULIA_VulkanReleaseQueryPoint(point);
        point = next;
    }
    SDL_free(query);
}
#endif
