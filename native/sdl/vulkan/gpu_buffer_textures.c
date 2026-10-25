#ifdef SDL_GPU_VULKAN
static int JULIA_VulkanBufferTextureABI(void) { return 1; }
static int JULIA_VulkanBufferTextureLimit(SDL_GPUDevice *device)
{
    VulkanRenderer *renderer = (VulkanRenderer *)device->driverData;
    return (int)SDL_min(renderer->physicalDeviceProperties.properties.limits.maxTexelBufferElements, (Uint32)SDL_MAX_SINT32 / 16);
}
static int JULIA_VulkanBufferTextureAlignment(SDL_GPUDevice *device, int format)
{
    VulkanRenderer *renderer = (VulkanRenderer *)device->driverData;
    if (format <= SDL_GPU_TEXTUREFORMAT_INVALID || format >= SDL_GPU_TEXTUREFORMAT_MAX_ENUM_VALUE) {
        SDL_SetError("Invalid buffer texture format"); return -1;
    }
    VkFormatProperties properties;
    renderer->vkGetPhysicalDeviceFormatProperties(renderer->physicalDevice, SDLToVK_TextureFormat[format], &properties);
    if (!(properties.bufferFeatures & VK_FORMAT_FEATURE_UNIFORM_TEXEL_BUFFER_BIT)) {
        SDL_SetError("Vulkan format does not support uniform texel buffers"); return -1;
    }
    return (int)renderer->physicalDeviceProperties.properties.limits.minTexelBufferOffsetAlignment;
}
static void *JULIA_VulkanBufferStorageIdentity(SDL_GPUBuffer *buffer)
{
    VulkanBufferContainer *container = (VulkanBufferContainer *)buffer;
    return container ? (void *)(uintptr_t)container->activeBuffer->buffer : NULL;
}
static SDL_GPUTexture *JULIA_VulkanCreateBufferTexture(
    SDL_GPUDevice *device, SDL_GPUBuffer *buffer, int format, Uint32 offset, Uint32 texels, Uint32 stride)
{
    VulkanRenderer *renderer = (VulkanRenderer *)device->driverData;
    VulkanBufferContainer *owner = (VulkanBufferContainer *)buffer;
    int alignment = JULIA_VulkanBufferTextureAlignment(device, format);
    if (!owner || alignment <= 0 || !stride || !texels || offset % alignment ||
        texels > (Uint32)JULIA_VulkanBufferTextureLimit(device) ||
        (Uint64)offset + (Uint64)texels * stride > owner->activeBuffer->size) {
        SDL_SetError("Invalid or unsupported native buffer texture range"); return NULL;
    }
    VulkanTexture *texture = SDL_calloc(1, sizeof(*texture));
    VulkanTextureContainer *container = SDL_calloc(1, sizeof(*container));
    VulkanTexture **textures = SDL_calloc(1, sizeof(*textures));
    if (!texture || !container || !textures) {
        SDL_free(texture); SDL_free(container); SDL_free(textures); SDL_OutOfMemory(); return NULL;
    }
    VkBufferViewCreateInfo info = {0};
    info.sType = VK_STRUCTURE_TYPE_BUFFER_VIEW_CREATE_INFO;
    info.buffer = owner->activeBuffer->buffer;
    info.format = SDLToVK_TextureFormat[format];
    info.offset = offset;
    info.range = (VkDeviceSize)texels * stride;
    VkFormat formats[3];
    Uint32 originalKind;
    switch (info.format) {
    case VK_FORMAT_R32_SFLOAT: case VK_FORMAT_R32_SINT: case VK_FORMAT_R32_UINT:
        formats[0] = VK_FORMAT_R32_SFLOAT; formats[1] = VK_FORMAT_R32_SINT; formats[2] = VK_FORMAT_R32_UINT; break;
    case VK_FORMAT_R32G32_SFLOAT: case VK_FORMAT_R32G32_SINT: case VK_FORMAT_R32G32_UINT:
        formats[0] = VK_FORMAT_R32G32_SFLOAT; formats[1] = VK_FORMAT_R32G32_SINT; formats[2] = VK_FORMAT_R32G32_UINT; break;
    case VK_FORMAT_R16_SINT: case VK_FORMAT_R16_UINT:
        formats[0] = VK_FORMAT_R16_SFLOAT; formats[1] = VK_FORMAT_R16_SINT; formats[2] = VK_FORMAT_R16_UINT; break;
    case VK_FORMAT_R16G16B16A16_UINT:
        formats[0] = VK_FORMAT_R16G16B16A16_SFLOAT; formats[1] = VK_FORMAT_R16G16B16A16_SINT; formats[2] = VK_FORMAT_R16G16B16A16_UINT; break;
    default:
        formats[0] = VK_FORMAT_R32G32B32A32_SFLOAT; formats[1] = VK_FORMAT_R32G32B32A32_SINT; formats[2] = VK_FORMAT_R32G32B32A32_UINT; break;
    }
    for (originalKind = 0; originalKind < 3; originalKind++) if (formats[originalKind] == info.format) break;
    // GL leaves mismatched numeric sampler reads undefined. Typed aliases keep those statically active descriptors
    // valid while retaining the same bits, including samplers in branches the draw never executes.
    for (Uint32 i = 0; i < 3; i++) {
        info.format = formats[i];
        VkResult result = renderer->vkCreateBufferView(renderer->logicalDevice, &info, NULL, &texture->juliaTypedBufferViews[i]);
        if (result != VK_SUCCESS) {
            for (Uint32 j = 0; j < i; j++) renderer->vkDestroyBufferView(renderer->logicalDevice, texture->juliaTypedBufferViews[j], NULL);
            SDL_free(texture); SDL_free(container); SDL_free(textures);
            SDL_SetError("Cannot create Vulkan buffer texture: %d", result); return NULL;
        }
    }
    texture->juliaBufferView = texture->juliaTypedBufferViews[originalKind];
    texture->juliaRenderer = renderer;
    texture->juliaBufferOwner = owner->activeBuffer;
    SDL_AddAtomicInt(&owner->activeBuffer->referenceCount, 1);
    SDL_AddAtomicInt(&owner->activeBuffer->juliaTextureReferences, 1);
    SDL_AddAtomicInt(&owner->activeBuffer->usedRegion->allocation->referenceCount, 1);
    texture->container = container;
    texture->type = SDL_GPU_TEXTURETYPE_2D;
    texture->usage = SDL_GPU_TEXTUREUSAGE_SAMPLER;
    container->activeTexture = texture;
    container->textures = textures;
    textures[0] = texture;
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
static bool JULIA_VulkanReadSamplerKinds(VulkanRenderer *renderer, const SDL_GPUShaderCreateInfo *info, Uint32 *kinds)
{
    Uint32 images = 0;
    SDL_memset(kinds, 0, sizeof(Uint32) * MAX_TEXTURE_SAMPLERS_PER_STAGE);
    for (Uint32 i = 0; i < info->num_samplers; i++) {
        char key[64];
        SDL_snprintf(key, sizeof(key), "julia.gpu.sampler_kind.%u", i);
        Sint64 kind = SDL_GetNumberProperty(info->props, key, 0);
        if (kind < 0 || kind > 3) return SDL_SetError("Invalid Vulkan sampler descriptor kind");
        kinds[i] = (Uint32)kind;
        if (!kind) images++;
    }
    const VkPhysicalDeviceLimits *limits = &renderer->physicalDeviceProperties.properties.limits;
    if (images > limits->maxPerStageDescriptorSamplers || info->num_samplers > limits->maxPerStageDescriptorSampledImages)
        return SDL_SetError("Vulkan stage needs %u samplers and %u sampled resources; device allows %u and %u",
            images, info->num_samplers, limits->maxPerStageDescriptorSamplers, limits->maxPerStageDescriptorSampledImages);
    return true;
}
#endif
