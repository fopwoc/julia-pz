/* Julia extension to SDL 3.4.12. Compiled inside SDL_gpu_metal.m; not an upstream SDL API. */
#ifdef SDL_GPU_METAL
#define JULIA_VISIBILITY_API __attribute__((visibility("default")))
#define JULIA_VISIBILITY_SLOTS 65536

/* One counting slot per render pass that runs while a query is active; Metal accumulates draws within a pass. */
@interface JuliaVisibilityPool : NSObject
@property(nonatomic, strong) id<MTLDevice> device;
@property(nonatomic, strong) id<MTLBuffer> results;
@property(nonatomic, strong) NSMutableIndexSet *available;
@end
@implementation JuliaVisibilityPool
@end

@interface JuliaVisibilityQuery : NSObject
@property(nonatomic, strong) JuliaVisibilityPool *pool;
@property(nonatomic, strong) NSMutableIndexSet *slots;
@property(nonatomic, strong) NSCondition *completion;
@property(nonatomic, strong) NSError *error;
@property(nonatomic) NSUInteger pending;
@property(nonatomic) BOOL ended;
@end
@implementation JuliaVisibilityQuery
- (void)dealloc
{
    @synchronized (_pool) { [_pool.available addIndexes:_slots]; }
}
@end

static JuliaVisibilityPool *juliaVisibilityPool;
static JuliaVisibilityQuery *juliaVisibilityActive;

static JuliaVisibilityPool *JULIA_MetalVisibilityPool(MetalRenderer *renderer)
{
    if (juliaVisibilityPool && juliaVisibilityPool.device == renderer->device) return juliaVisibilityPool;
    JuliaVisibilityPool *pool = [JuliaVisibilityPool new];
    pool.device = renderer->device;
    pool.results = [renderer->device newBufferWithLength:JULIA_VISIBILITY_SLOTS * sizeof(uint64_t)
                                                 options:MTLResourceStorageModeShared];
    if (!pool.results) return nil;
    pool.available = [NSMutableIndexSet indexSetWithIndexesInRange:NSMakeRange(0, JULIA_VISIBILITY_SLOTS)];
    juliaVisibilityPool = pool;
    juliaVisibilityActive = nil;
    return pool;
}

/* Every pass carries the buffer so a query can begin before any later pass without re-encoding. */
static void JULIA_MetalVisibilityAttach(MetalRenderer *renderer, MTLRenderPassDescriptor *descriptor)
{
    JuliaVisibilityPool *pool = JULIA_MetalVisibilityPool(renderer);
    if (pool) descriptor.visibilityResultBuffer = pool.results;
}

static void JULIA_MetalVisibilityBegin(MetalCommandBuffer *command)
{
    JuliaVisibilityQuery *query = juliaVisibilityActive;
    if (!query || !command->renderEncoder || query.pool.device != command->renderer->device) return;
    NSUInteger slot;
    @synchronized (query.pool) {
        slot = query.pool.available.firstIndex;
        if (slot != NSNotFound) [query.pool.available removeIndex:slot];
    }
    if (slot == NSNotFound) {
        // The query cannot be answered truthfully; reads report the failure instead of a partial count.
        [query.completion lock];
        query.error = [NSError errorWithDomain:@"Julia" code:1 userInfo:@{NSLocalizedDescriptionKey : @"visibility slot pool exhausted"}];
        [query.completion unlock];
        return;
    }
    ((uint64_t *)query.pool.results.contents)[slot] = 0;
    [query.slots addIndex:slot];
    [command->renderEncoder setVisibilityResultMode:MTLVisibilityResultModeCounting offset:slot * sizeof(uint64_t)];
    [query.completion lock];
    query.pending += 1;
    [query.completion unlock];
    [command->handle addCompletedHandler:^(id<MTLCommandBuffer> completed) {
        [query.completion lock];
        if (completed.error && !query.error) query.error = completed.error;
        query.pending -= 1;
        [query.completion broadcast];
        [query.completion unlock];
    }];
}

JULIA_VISIBILITY_API int SDLCALL JULIA_SDL_VisibilityABI(void) { return 1; }

/* The caller ends any open render pass first; passes begun from here on are counted. */
JULIA_VISIBILITY_API void *SDLCALL JULIA_SDL_BeginVisibilityQuery(SDL_GPUDevice *device)
{
    @autoreleasepool {
        if (!device || SDL_strcmp(SDL_GetGPUDeviceDriver(device), "metal") != 0) {
            SDL_SetError("Julia visibility queries require the SDL Metal backend");
            return NULL;
        }
        if (juliaVisibilityActive) { SDL_SetError("A visibility query is already active"); return NULL; }
        JuliaVisibilityPool *pool = JULIA_MetalVisibilityPool((MetalRenderer *)device->driverData);
        if (!pool) { SDL_SetError("Cannot allocate the Metal visibility result buffer"); return NULL; }
        JuliaVisibilityQuery *query = [JuliaVisibilityQuery new];
        query.pool = pool;
        query.slots = [NSMutableIndexSet indexSet];
        query.completion = [NSCondition new];
        juliaVisibilityActive = query;
        return (__bridge_retained void *)query;
    }
}

/* The caller ends the open render pass first, so its encoder has written every counted sample. */
JULIA_VISIBILITY_API int SDLCALL JULIA_SDL_EndVisibilityQuery(void *opaque)
{
    @autoreleasepool {
        JuliaVisibilityQuery *query = (__bridge JuliaVisibilityQuery *)opaque;
        if (!query || query != juliaVisibilityActive) { SDL_SetError("Visibility query is not active"); return -1; }
        juliaVisibilityActive = nil;
        [query.completion lock];
        query.ended = YES;
        [query.completion unlock];
        return 0;
    }
}

JULIA_VISIBILITY_API int SDLCALL JULIA_SDL_ReadVisibilityQuery(void *opaque, int wait, uint64_t *samples)
{
    @autoreleasepool {
        JuliaVisibilityQuery *query = (__bridge JuliaVisibilityQuery *)opaque;
        if (!query || !samples) { SDL_SetError("Invalid visibility query read"); return -1; }
        [query.completion lock];
        while (wait && query.ended && query.pending > 0 && !query.error) [query.completion wait];
        BOOL ended = query.ended, complete = query.pending == 0;
        NSError *error = query.error;
        [query.completion unlock];
        if (!ended) { SDL_SetError("Visibility query is still active"); return -1; }
        if (error) { SDL_SetError("Visibility query failed: %s", error.localizedDescription.UTF8String); return -1; }
        if (!complete) return 0;
        const uint64_t *results = (const uint64_t *)query.pool.results.contents;
        uint64_t total = 0;
        for (NSUInteger slot = query.slots.firstIndex; slot != NSNotFound; slot = [query.slots indexGreaterThanIndex:slot]) {
            if (results[slot] > UINT64_MAX - total) { SDL_SetError("Visibility sample count overflow"); return -1; }
            total += results[slot];
        }
        *samples = total;
        return 1;
    }
}

JULIA_VISIBILITY_API void SDLCALL JULIA_SDL_ReleaseVisibilityQuery(void *opaque)
{
    @autoreleasepool {
        if (!opaque) return;
        JuliaVisibilityQuery *query = CFBridgingRelease(opaque);
        if (query == juliaVisibilityActive) juliaVisibilityActive = nil;
    }
}
#endif
