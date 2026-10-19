/* Julia extension to SDL 3.4.12. Compiled inside SDL_gpu_metal.m; not an upstream SDL API. */
#ifdef SDL_GPU_METAL
#import <Metal/MTLCounters.h>
#define JULIA_GPU_API __attribute__((visibility("default")))

@interface JuliaTimestampClock : NSObject
@property(nonatomic, strong) id<MTLDevice> device;
@property(nonatomic, strong) id<MTLCounterSampleBuffer> samples;
@property(nonatomic, strong) id<MTLBuffer> marker;
@property(nonatomic, strong) NSMutableIndexSet *available;
@property(nonatomic) uint64_t cpuOrigin;
@property(nonatomic) uint64_t gpuOrigin;
@property(nonatomic) long double nanosecondsPerTick;
@end
@implementation JuliaTimestampClock
@end

@interface JuliaTimestamp : NSObject
@property(nonatomic, strong) JuliaTimestampClock *clock;
@property(nonatomic, strong) id<MTLCommandBuffer> command;
@property(nonatomic) NSUInteger index;
@property(nonatomic, strong) NSCondition *completion;
@property(nonatomic, strong) NSError *error;
@property(nonatomic) BOOL complete;
@end
@implementation JuliaTimestamp
- (void)dealloc
{
    @synchronized (_clock) { [_clock.available addIndex:_index]; }
}
@end

JULIA_GPU_API int SDLCALL JULIA_SDL_TimestampABI(void) { return 1; }

JULIA_GPU_API void *SDLCALL JULIA_SDL_CreateTimestampClock(SDL_GPUDevice *device)
{
    @autoreleasepool {
        if (!device || SDL_strcmp(SDL_GetGPUDeviceDriver(device), "metal") != 0) {
            SDL_SetError("Julia timestamps require the SDL Metal backend");
            return NULL;
        }
        MetalRenderer *renderer = (MetalRenderer *)device->driverData;
        id<MTLDevice> metal = renderer->device;
        if (![metal supportsCounterSampling:MTLCounterSamplingPointAtStageBoundary]) {
            SDL_SetError("Metal device does not support stage-boundary counter sampling");
            return NULL;
        }
        id<MTLCounterSet> timestamps = nil;
        for (id<MTLCounterSet> counter in metal.counterSets)
            if ([counter.name isEqualToString:MTLCommonCounterSetTimestamp]) timestamps = counter;
        if (!timestamps) { SDL_SetError("Metal timestamp counter set is unavailable"); return NULL; }
        MTLCounterSampleBufferDescriptor *descriptor = [MTLCounterSampleBufferDescriptor new];
        descriptor.counterSet = timestamps;
        descriptor.storageMode = MTLStorageModeShared;
        descriptor.sampleCount = 4096;
        descriptor.label = @"Julia GPU timestamps";
        NSError *error = nil;
        id<MTLCounterSampleBuffer> samples = [metal newCounterSampleBufferWithDescriptor:descriptor error:&error];
        if (!samples) { SDL_SetError("Cannot create Metal timestamp buffer: %s", error.localizedDescription.UTF8String); return NULL; }
        JuliaTimestampClock *clock = [JuliaTimestampClock new];
        clock.device = metal;
        clock.samples = samples;
        clock.marker = [metal newBufferWithLength:4 options:MTLResourceStorageModeShared];
        if (!clock.marker) { SDL_SetError("Cannot allocate GPU timestamp marker buffer"); return NULL; }
        clock.available = [NSMutableIndexSet indexSetWithIndexesInRange:NSMakeRange(0, 4096)];
        uint64_t cpu0, gpu0, cpu1, gpu1;
        [metal sampleTimestamps:&cpu0 gpuTimestamp:&gpu0];
        SDL_Delay(5);
        [metal sampleTimestamps:&cpu1 gpuTimestamp:&gpu1];
        if (cpu1 <= cpu0 || gpu1 <= gpu0) { SDL_SetError("Metal timestamp clock calibration failed"); return NULL; }
        clock.cpuOrigin = cpu0;
        clock.gpuOrigin = gpu0;
        clock.nanosecondsPerTick = (long double)(cpu1 - cpu0) / (long double)(gpu1 - gpu0);
        return (__bridge_retained void *)clock;
    }
}

JULIA_GPU_API void *SDLCALL JULIA_SDL_RecordTimestamp(void *opaqueClock, SDL_GPUCommandBuffer *buffer)
{
    @autoreleasepool {
        JuliaTimestampClock *clock = (__bridge JuliaTimestampClock *)opaqueClock;
        MetalCommandBuffer *command = (MetalCommandBuffer *)buffer;
        if (!clock || !command || command->renderer->device != clock.device ||
            command->renderEncoder || command->computeEncoder || command->blitEncoder) {
            SDL_SetError("Timestamp requires the owning device and a command buffer with no active encoder");
            return NULL;
        }
        NSUInteger slot;
        @synchronized (clock) {
            slot = clock.available.firstIndex;
            if (slot != NSNotFound) [clock.available removeIndex:slot];
        }
        if (slot == NSNotFound) { SDL_SetError("4096 GPU timestamps are still pending; timestamp pool exhausted"); return NULL; }
        JuliaTimestamp *point = [JuliaTimestamp new];
        point.clock = clock;
        point.index = slot;
        point.command = command->handle;
        point.completion = [NSCondition new];
        MTLBlitPassDescriptor *pass = [MTLBlitPassDescriptor blitPassDescriptor];
        pass.sampleBufferAttachments[0].sampleBuffer = clock.samples;
        pass.sampleBufferAttachments[0].startOfEncoderSampleIndex = slot;
        pass.sampleBufferAttachments[0].endOfEncoderSampleIndex = MTLCounterDontSample;
        id<MTLBlitCommandEncoder> encoder = [command->handle blitCommandEncoderWithDescriptor:pass];
        if (!encoder) { SDL_SetError("Cannot encode Metal timestamp marker"); return NULL; }
        [encoder fillBuffer:clock.marker range:NSMakeRange(0, 4) value:0];
        [encoder endEncoding];
        // Deleted/reissued GL queries must keep their sample slot alive until the GPU finishes.
        [command->handle addCompletedHandler:^(id<MTLCommandBuffer> completed) {
            [point.completion lock];
            point.error = completed.error;
            point.complete = YES;
            point.command = nil;
            [point.completion broadcast];
            [point.completion unlock];
        }];
        return (__bridge_retained void *)point;
    }
}

JULIA_GPU_API int SDLCALL JULIA_SDL_ReadTimestamp(void *opaque, int wait, uint64_t *nanoseconds)
{
    @autoreleasepool {
        JuliaTimestamp *point = (__bridge JuliaTimestamp *)opaque;
        if (!point || !nanoseconds) { SDL_SetError("Invalid timestamp read"); return -1; }
        [point.completion lock];
        while (wait && !point.complete) [point.completion wait];
        BOOL complete = point.complete;
        NSError *error = point.error;
        [point.completion unlock];
        if (error) { SDL_SetError("Timestamp command failed: %s", error.localizedDescription.UTF8String); return -1; }
        if (!complete) return 0;
        NSData *resolved = [point.clock.samples resolveCounterRange:NSMakeRange(point.index, 1)];
        if (resolved.length != sizeof(MTLCounterResultTimestamp)) { SDL_SetError("Cannot resolve Metal timestamp"); return -1; }
        uint64_t gpu = ((const MTLCounterResultTimestamp *)resolved.bytes)->timestamp;
        if (gpu == MTLCounterErrorValue || gpu < point.clock.gpuOrigin) { SDL_SetError("Invalid Metal timestamp counter value: gpu=%llu origin=%llu slot=%lu", (unsigned long long)gpu, (unsigned long long)point.clock.gpuOrigin, (unsigned long)point.index); return -1; }
        long double value = point.clock.cpuOrigin + (long double)(gpu - point.clock.gpuOrigin) * point.clock.nanosecondsPerTick;
        if (value < 0 || value >= 18446744073709551616.0L) { SDL_SetError("Metal timestamp conversion overflow"); return -1; }
        *nanoseconds = (uint64_t)value;
        return 1;
    }
}

JULIA_GPU_API void SDLCALL JULIA_SDL_ReleaseTimestamp(void *point)
{
    @autoreleasepool { if (point) { id released = CFBridgingRelease(point); (void)released; } }
}
JULIA_GPU_API void SDLCALL JULIA_SDL_ReleaseTimestampClock(void *clock)
{
    @autoreleasepool { if (clock) { id released = CFBridgingRelease(clock); (void)released; } }
}
#endif
