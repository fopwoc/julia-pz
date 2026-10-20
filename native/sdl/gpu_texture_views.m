/* Julia extension to SDL 3.4.12. Compiled inside SDL_gpu_metal.m; not an upstream SDL API. */
#ifdef SDL_GPU_METAL
#define JULIA_VIEW_API __attribute__((visibility("default")))
JULIA_VIEW_API int SDLCALL JULIA_SDL_TextureViewABI(void) { return 1; }

JULIA_VIEW_API SDL_GPUTexture *SDLCALL JULIA_SDL_CreateTextureView(
    SDL_GPUTexture *source, Uint32 type, Uint32 level, Uint32 levels, Uint32 layer, Uint32 layers)
{
    @autoreleasepool {
        MetalTextureContainer *owner = (MetalTextureContainer *)source;
        if (!owner || (owner->header.info.type != SDL_GPU_TEXTURETYPE_2D && owner->header.info.type != SDL_GPU_TEXTURETYPE_2D_ARRAY) ||
            (type != SDL_GPU_TEXTURETYPE_2D && type != SDL_GPU_TEXTURETYPE_2D_ARRAY) || levels == 0 || layers == 0 ||
            level >= owner->header.info.num_levels || levels > owner->header.info.num_levels - level ||
            layer >= owner->header.info.layer_count_or_depth || layers > owner->header.info.layer_count_or_depth - layer ||
            (type == SDL_GPU_TEXTURETYPE_2D && layers != 1)) {
            SDL_SetError("Invalid native texture view target or subresource range"); return NULL;
        }
        id<MTLTexture> parent = owner->activeTexture->handle;
        id<MTLTexture> handle = [parent newTextureViewWithPixelFormat:parent.pixelFormat
            textureType:(type == SDL_GPU_TEXTURETYPE_2D ? MTLTextureType2D : MTLTextureType2DArray)
            levels:NSMakeRange(level, levels) slices:NSMakeRange(layer, layers)];
        if (!handle) { SDL_SetError("Metal rejected shared texture subresource view"); return NULL; }
        MetalTexture *texture = SDL_calloc(1, sizeof(MetalTexture));
        MetalTextureContainer *container = SDL_calloc(1, sizeof(MetalTextureContainer));
        MetalTexture **textures = SDL_calloc(1, sizeof(MetalTexture *));
        if (!texture || !container || !textures) {
            SDL_free(texture); SDL_free(container); SDL_free(textures); SDL_SetError("Cannot allocate texture view bookkeeping"); return NULL;
        }
        // A view retains its Metal parent/storage independently of the SDL source name. Never cycle that store.
        owner->canBeCycled = false;
        texture->handle = handle;
        textures[0] = texture;
        container->activeTexture = texture;
        container->textures = textures;
        container->textureCapacity = container->textureCount = 1;
        container->header.info = owner->header.info;
        container->header.info.type = (SDL_GPUTextureType)type;
        container->header.info.width = SDL_max(1u, owner->header.info.width >> level);
        container->header.info.height = SDL_max(1u, owner->header.info.height >> level);
        container->header.info.layer_count_or_depth = layers;
        container->header.info.num_levels = levels;
        container->header.info.props = SDL_CreateProperties();
        return (SDL_GPUTexture *)container;
    }
}
#endif
