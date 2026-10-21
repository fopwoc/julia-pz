/* Reflected GL sampler arrays retain their contiguous logical slots, but share one Vulkan descriptor binding. */
static int SDLCALL JULIA_VulkanSamplerArgumentsABI(void) { return 2; }

static bool JULIA_VulkanReadSamplerWidths(VulkanRenderer *renderer, const SDL_GPUShaderCreateInfo *info, Uint32 *widths)
{
    if (SDL_GetBooleanProperty(info->props, "julia.gpu.requires_draw_parameters", false) && !renderer->supports.KHR_shader_draw_parameters)
        return SDL_SetError("Vulkan instance-index translation requires VK_KHR_shader_draw_parameters");
    SDL_memset(widths, 0, sizeof(Uint32) * MAX_TEXTURE_SAMPLERS_PER_STAGE);
    Uint32 limit = renderer->physicalDeviceProperties.properties.limits.maxPerStageDescriptorSamplers;
    if (info->num_samplers > limit)
        return SDL_SetError("Vulkan shader needs %u samplers, device supports %u", info->num_samplers, limit);
    for (Uint32 i = 0; i < info->num_samplers; ) {
        char key[64];
        SDL_snprintf(key, sizeof(key), "julia.gpu.sampler_width.%u", i);
        Sint64 width = SDL_GetNumberProperty(info->props, key, 1);
        if (width <= 0 || (Uint64)width > info->num_samplers - i)
            return SDL_SetError("Invalid Vulkan sampler array width %lld at slot %u", (long long)width, i);
        widths[i] = (Uint32)width;
        for (Uint32 j = 1; j < (Uint32)width; j++) {
            SDL_snprintf(key, sizeof(key), "julia.gpu.sampler_width.%u", i + j);
            if (SDL_GetNumberProperty(info->props, key, 0) != 0)
                return SDL_SetError("Overlapping Vulkan sampler array at slot %u", i + j);
        }
        i += (Uint32)width;
    }
    return true;
}

static Uint32 JULIA_VulkanSamplerBinding(const DescriptorSetLayout *layout, Uint32 slot, Uint32 *element)
{
    Uint32 binding = slot;
    while (binding && layout->juliaSamplerWidths[binding] == 0) binding--;
    *element = slot - binding;
    return binding;
}
