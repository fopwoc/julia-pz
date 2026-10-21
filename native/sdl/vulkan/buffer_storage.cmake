# GL storage is untyped: an indirect/vertex buffer may subsequently be bound as an SSBO.
set(backend "${sdl_patched}/src/gpu/vulkan/SDL_gpu_vulkan.c")
file(READ "${backend}" source)
julia_replace([=[    if (type == VULKAN_BUFFER_TYPE_UNIFORM) {
        vulkanUsageFlags |= VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;]=] [=[    if (type == VULKAN_BUFFER_TYPE_GPU) vulkanUsageFlags |= VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    if (type == VULKAN_BUFFER_TYPE_UNIFORM) {
        vulkanUsageFlags |= VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;]=] 1)
file(WRITE "${backend}" "${source}")
