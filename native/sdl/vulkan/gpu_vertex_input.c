/* Compiled inside SDL_gpu_vulkan.c; public callers use the device-selected extension table. */
static int SDLCALL JULIA_VulkanVertexInputABI(void) { return 2; }

static Uint32 JULIA_VulkanInitVertexInput(VulkanRenderer *renderer, VkPhysicalDeviceVertexAttributeDivisorFeaturesEXT *features)
{
    SDL_zero(*features);
    features->sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VERTEX_ATTRIBUTE_DIVISOR_FEATURES_EXT;
    renderer->juliaMaxVertexDivisor = 1;
    renderer->juliaZeroVertexDivisor = false;
    renderer->juliaVertexAccessBeyondStride = false;
    PFN_vkGetPhysicalDeviceFeatures2KHR getFeatures = (PFN_vkGetPhysicalDeviceFeatures2KHR)
        vkGetInstanceProcAddr(renderer->instance, "vkGetPhysicalDeviceFeatures2KHR");
    if (!getFeatures || !renderer->supportsPhysicalDeviceProperties2) return 0;
    if (renderer->supports.KHR_portability_subset) {
        VkPhysicalDevicePortabilitySubsetFeaturesKHR portability = { 0 };
        portability.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PORTABILITY_SUBSET_FEATURES_KHR;
        VkPhysicalDeviceFeatures2 query = { 0 };
        query.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
        query.pNext = &portability;
        getFeatures(renderer->physicalDevice, &query);
        renderer->juliaVertexAccessBeyondStride = portability.vertexAttributeAccessBeyondStride;
    }
    Uint32 count = 0;
    if (renderer->vkEnumerateDeviceExtensionProperties(renderer->physicalDevice, NULL, &count, NULL) != VK_SUCCESS) return 0;
    VkExtensionProperties *extensions = SDL_calloc(count, sizeof(*extensions));
    if (!extensions) return 0;
    VkResult result = renderer->vkEnumerateDeviceExtensionProperties(renderer->physicalDevice, NULL, &count, extensions);
    bool found = false;
    if (result == VK_SUCCESS) {
        for (Uint32 i = 0; i < count; i++)
            if (SDL_strcmp(extensions[i].extensionName, VK_EXT_VERTEX_ATTRIBUTE_DIVISOR_EXTENSION_NAME) == 0) found = true;
    }
    SDL_free(extensions);
    if (!found) return 0;
    VkPhysicalDeviceFeatures2 query = { 0 };
    query.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    query.pNext = features;
    getFeatures(renderer->physicalDevice, &query);
    if (!features->vertexAttributeInstanceRateDivisor) return 0;
    renderer->juliaZeroVertexDivisor = features->vertexAttributeInstanceRateZeroDivisor;
    VkPhysicalDeviceVertexAttributeDivisorPropertiesEXT divisors = { 0 };
    divisors.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VERTEX_ATTRIBUTE_DIVISOR_PROPERTIES_EXT;
    VkPhysicalDeviceProperties2 properties = { 0 };
    properties.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
    properties.pNext = &divisors;
    renderer->vkGetPhysicalDeviceProperties2KHR(renderer->physicalDevice, &properties);
    renderer->juliaMaxVertexDivisor = divisors.maxVertexAttribDivisor;
    return 1;
}

static Sint64 JULIA_VulkanVertexRate(SDL_PropertiesID properties, Uint32 slot)
{
    char key[64];
    SDL_snprintf(key, sizeof(key), "julia.gpu.vertex_divisor.%u", slot);
    return SDL_GetNumberProperty(properties, key, 1);
}

static bool JULIA_VulkanValidateVertexInput(VulkanRenderer *renderer, const SDL_GPUGraphicsPipelineCreateInfo *info)
{
    for (Uint32 i = 0; i < info->vertex_input_state.num_vertex_buffers; i++) {
        const SDL_GPUVertexBufferDescription *binding = &info->vertex_input_state.vertex_buffer_descriptions[i];
        if (binding->input_rate != SDL_GPU_VERTEXINPUTRATE_INSTANCE) continue;
        Sint64 rate = JULIA_VulkanVertexRate(info->props, binding->slot);
        if (rate == -1 && renderer->supports.KHR_portability_subset && !renderer->juliaVertexAccessBeyondStride)
            return SDL_SetError("Vulkan device cannot fetch constant generic attributes with zero stride");
        /* No representable instance index reaches UINT_MAX: its GL fetch is exactly a zero divisor. */
        if (rate == SDL_MAX_UINT32 && renderer->juliaZeroVertexDivisor) continue;
        if (rate < -1 || rate == 0 || (rate > 0 && (Uint64)rate > renderer->juliaMaxVertexDivisor))
            return SDL_SetError("Vulkan vertex divisor %lld exceeds the device limit %u", (long long)rate, renderer->juliaMaxVertexDivisor);
    }
    return true;
}

static void JULIA_VulkanApplyVertexInput(const SDL_GPUGraphicsPipelineCreateInfo *info,
    VkPipelineVertexInputStateCreateInfo *state, VkVertexInputBindingDescription *bindings,
    VkPipelineVertexInputDivisorStateCreateInfoEXT *divisorState, VkVertexInputBindingDivisorDescriptionEXT *divisors)
{
    Uint32 count = 0;
    for (Uint32 i = 0; i < info->vertex_input_state.num_vertex_buffers; i++) {
        if (bindings[i].inputRate != VK_VERTEX_INPUT_RATE_INSTANCE) continue;
        Sint64 rate = JULIA_VulkanVertexRate(info->props, bindings[i].binding);
        if (rate == -1) {
            bindings[i].inputRate = VK_VERTEX_INPUT_RATE_VERTEX;
            bindings[i].stride = 0;
        } else if (rate != 1) {
            divisors[count].binding = bindings[i].binding;
            divisors[count++].divisor = rate == SDL_MAX_UINT32 ? 0 : (Uint32)rate;
        }
    }
    if (count) {
        SDL_zero(*divisorState);
        divisorState->sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_DIVISOR_STATE_CREATE_INFO_EXT;
        divisorState->vertexBindingDivisorCount = count;
        divisorState->pVertexBindingDivisors = divisors;
        divisorState->pNext = state->pNext;
        state->pNext = divisorState;
    }
}
