#ifdef SDL_GPU_VULKAN
static int JULIA_VulkanTextureViewABI(void) { return 1; }

static SDL_GPUTexture *JULIA_VulkanCreateTextureView(
    SDL_GPUTexture *source, Uint32 type, Uint32 level, Uint32 levels, Uint32 layer, Uint32 layers)
{
    VulkanTextureContainer *owner = (VulkanTextureContainer *)source;
    if (!owner || (owner->header.info.type != SDL_GPU_TEXTURETYPE_2D && owner->header.info.type != SDL_GPU_TEXTURETYPE_2D_ARRAY) ||
        (type != SDL_GPU_TEXTURETYPE_2D && type != SDL_GPU_TEXTURETYPE_2D_ARRAY) || !levels || !layers ||
        level >= owner->header.info.num_levels || levels > owner->header.info.num_levels - level ||
        layer >= owner->header.info.layer_count_or_depth || layers > owner->header.info.layer_count_or_depth - layer ||
        (type == SDL_GPU_TEXTURETYPE_2D && layers != 1)) {
        SDL_SetError("Invalid native texture view target or subresource range"); return NULL;
    }
    VulkanTexture *parent = owner->activeTexture;
    VulkanTexture *root = parent->juliaViewOwner ? parent->juliaViewOwner : parent;
    VulkanRenderer *renderer = parent->juliaRenderer;
    if (!renderer || !root->usedRegion) { SDL_SetError("Cannot view an unmanaged Vulkan image"); return NULL; }
    VulkanTexture *texture = SDL_calloc(1, sizeof(*texture));
    VulkanTextureContainer *container = SDL_calloc(1, sizeof(*container));
    VulkanTexture **textures = SDL_calloc(1, sizeof(*textures));
    if (!texture || !container || !textures) {
        SDL_free(texture); SDL_free(container); SDL_free(textures); SDL_OutOfMemory(); return NULL;
    }
    texture->juliaRenderer = renderer;
    texture->juliaViewOwner = root;
    texture->juliaBaseLevel = parent->juliaBaseLevel + level;
    texture->juliaBaseLayer = parent->juliaBaseLayer + layer;
    texture->image = parent->image;
    texture->swizzle = parent->swizzle;
    texture->aspectFlags = parent->aspectFlags;
    texture->depth = 1;
    texture->levelCount = levels;
    texture->layerCount = layers;
    texture->type = (SDL_GPUTextureType)type;
    texture->usage = parent->usage;
    SDL_AddAtomicInt(&root->referenceCount, 1);
    // SDL defragmentation must not relocate the shared image while a view references it.
    SDL_AddAtomicInt(&root->usedRegion->allocation->referenceCount, 1);
    owner->canBeCycled = false;
    texture->subresources = SDL_calloc((size_t)layers * levels, sizeof(VulkanTextureSubresource));
    if (!texture->subresources) goto failed;
    texture->subresourceCount = layers * levels;
    VkImageViewCreateInfo view = {0};
    view.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    view.image = texture->image;
    view.viewType = type == SDL_GPU_TEXTURETYPE_2D ? VK_IMAGE_VIEW_TYPE_2D : VK_IMAGE_VIEW_TYPE_2D_ARRAY;
    view.format = SDLToVK_TextureFormat[owner->header.info.format];
    view.components = texture->swizzle;
    view.subresourceRange.aspectMask = texture->aspectFlags & ~VK_IMAGE_ASPECT_STENCIL_BIT;
    view.subresourceRange.baseMipLevel = texture->juliaBaseLevel;
    view.subresourceRange.levelCount = levels;
    view.subresourceRange.baseArrayLayer = texture->juliaBaseLayer;
    view.subresourceRange.layerCount = layers;
    if (renderer->vkCreateImageView(renderer->logicalDevice, &view, NULL, &texture->fullView) != VK_SUCCESS) goto failed;
    for (Uint32 i = 0; i < layers; i++) for (Uint32 j = 0; j < levels; j++) {
        VulkanTextureSubresource *sub = &texture->subresources[i * levels + j];
        sub->parent = texture;
        sub->layer = i + texture->juliaBaseLayer;
        sub->level = j + texture->juliaBaseLevel;
        if (texture->usage & SDL_GPU_TEXTUREUSAGE_COLOR_TARGET) {
            sub->renderTargetViews = SDL_calloc(1, sizeof(VkImageView));
            if (!sub->renderTargetViews || !VULKAN_INTERNAL_CreateRenderTargetView(renderer, texture,
                sub->layer, sub->level, view.format, texture->swizzle, sub->renderTargetViews)) goto failed;
        }
        if (texture->usage & (SDL_GPU_TEXTUREUSAGE_COMPUTE_STORAGE_WRITE | SDL_GPU_TEXTUREUSAGE_COMPUTE_STORAGE_SIMULTANEOUS_READ_WRITE)) {
            if (!VULKAN_INTERNAL_CreateSubresourceView(renderer, &owner->header.info, texture,
                sub->layer, sub->level, texture->swizzle, &sub->computeWriteView)) goto failed;
        }
        if (texture->usage & SDL_GPU_TEXTUREUSAGE_DEPTH_STENCIL_TARGET) {
            if (!VULKAN_INTERNAL_CreateSubresourceView(renderer, &owner->header.info, texture,
                sub->layer, sub->level, texture->swizzle, &sub->depthStencilView)) goto failed;
        }
    }
    container->header.info = owner->header.info;
    container->header.info.type = (SDL_GPUTextureType)type;
    container->header.info.width = SDL_max(1u, owner->header.info.width >> level);
    container->header.info.height = SDL_max(1u, owner->header.info.height >> level);
    container->header.info.layer_count_or_depth = layers;
    container->header.info.num_levels = levels;
    container->header.info.props = SDL_CreateProperties();
    container->activeTexture = texture;
    container->textureCapacity = container->textureCount = 1;
    container->textures = textures;
    textures[0] = texture;
    texture->container = container;
    return (SDL_GPUTexture *)container;
failed:
    SDL_SetError("Cannot create Vulkan texture view");
    VULKAN_INTERNAL_DestroyTexture(renderer, texture);
    SDL_free(container); SDL_free(textures);
    return NULL;
}
#endif
