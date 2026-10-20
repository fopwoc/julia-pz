/* Julia extension to SDL 3.4.12. Compiled inside SDL_gpu_metal.m; not an upstream SDL API. */
#ifdef SDL_GPU_METAL
#define JULIA_BUFFER_API __attribute__((visibility("default")))

JULIA_BUFFER_API int SDLCALL JULIA_SDL_BufferTextureABI(void) { return 1; }

JULIA_BUFFER_API int SDLCALL JULIA_SDL_BufferTextureLimit(SDL_GPUDevice *device)
{
    MetalRenderer *renderer = (MetalRenderer *)device->driverData;
    // Metal Mac2 and Apple3+ allow 256M texels. Keep every supported 16-byte texel within Java's signed buffer size.
    Uint64 limit = SDL_min((Uint64)renderer->device.maxBufferLength / 16, (Uint64)SDL_MAX_SINT32 / 16);
    return (int)SDL_min(limit, (Uint64)64 * 1024 * 1024);
}

JULIA_BUFFER_API int SDLCALL JULIA_SDL_BufferTextureAlignment(SDL_GPUDevice *device, int format)
{
    MetalRenderer *renderer = (MetalRenderer *)device->driverData;
    MTLPixelFormat pixel = SDLToMetal_TextureFormat((SDL_GPUTextureFormat)format);
    if (pixel == MTLPixelFormatInvalid) { SDL_SetError("Unsupported buffer texture pixel format"); return -1; }
    return (int)[renderer->device minimumTextureBufferAlignmentForPixelFormat:pixel];
}

JULIA_BUFFER_API void *SDLCALL JULIA_SDL_BufferStorageIdentity(SDL_GPUBuffer *buffer)
{
    MetalBufferContainer *container = (MetalBufferContainer *)buffer;
    return container ? (__bridge void *)container->activeBuffer->handle : NULL;
}

JULIA_BUFFER_API SDL_GPUTexture *SDLCALL JULIA_SDL_CreateBufferTexture(
    SDL_GPUDevice *device, SDL_GPUBuffer *buffer, int format, Uint32 offset, Uint32 texels, Uint32 stride)
{
    @autoreleasepool {
        MetalRenderer *renderer = (MetalRenderer *)device->driverData;
        MetalBufferContainer *owner = (MetalBufferContainer *)buffer;
        MTLPixelFormat pixel = SDLToMetal_TextureFormat((SDL_GPUTextureFormat)format);
        if (!owner || pixel == MTLPixelFormatInvalid || texels == 0 || stride == 0 ||
            (Uint64)offset + (Uint64)texels * stride > owner->size ||
            texels > (Uint32)JULIA_SDL_BufferTextureLimit(device) ||
            offset % [renderer->device minimumTextureBufferAlignmentForPixelFormat:pixel] != 0) {
            SDL_SetError("Invalid or unsupported native buffer texture range");
            return NULL;
        }
        MTLTextureDescriptor *descriptor = [MTLTextureDescriptor textureBufferDescriptorWithPixelFormat:pixel
            width:texels resourceOptions:owner->activeBuffer->handle.resourceOptions usage:MTLTextureUsageShaderRead];
        id<MTLTexture> handle = [owner->activeBuffer->handle newTextureWithDescriptor:descriptor offset:offset bytesPerRow:(NSUInteger)texels * stride];
        if (!handle) { SDL_SetError("Metal could not create a texture view of GPU buffer storage"); return NULL; }
        MetalTexture *texture = SDL_calloc(1, sizeof(MetalTexture));
        MetalTextureContainer *container = SDL_calloc(1, sizeof(MetalTextureContainer));
        MetalTexture **textures = SDL_calloc(1, sizeof(MetalTexture *));
        if (!texture || !container || !textures) {
            SDL_free(texture); SDL_free(container); SDL_free(textures); SDL_SetError("Cannot allocate buffer texture bookkeeping"); return NULL;
        }
        texture->handle = handle;
        texture->juliaBufferOwner = owner->activeBuffer;
        // Keep SDL's physical buffer alive after GL deletion, reallocation, and deferred command completion.
        SDL_AddAtomicInt(&owner->activeBuffer->referenceCount, 1);
        SDL_AddAtomicInt(&owner->activeBuffer->juliaTextureReferences, 1);
        textures[0] = texture;
        container->activeTexture = texture;
        container->textures = textures;
        container->textureCapacity = container->textureCount = 1;
        container->header.info.type = SDL_GPU_TEXTURETYPE_2D;
        container->header.info.format = (SDL_GPUTextureFormat)format;
        container->header.info.usage = SDL_GPU_TEXTUREUSAGE_SAMPLER;
        container->header.info.width = texels;
        container->header.info.height = 1;
        container->header.info.layer_count_or_depth = 1;
        container->header.info.num_levels = 1;
        container->header.info.sample_count = SDL_GPU_SAMPLECOUNT_1;
        container->header.info.props = SDL_CreateProperties();
        return (SDL_GPUTexture *)container;
    }
}
#endif
