/* Backend-local table; unknown features remain unavailable instead of borrowing Metal implementations. */
SDL_FunctionPointer JULIA_VulkanGetExtension(const char *name)
{
    if (SDL_strcmp(name, "JULIA_SDL_VertexInputABI") == 0) return (SDL_FunctionPointer)JULIA_VulkanVertexInputABI;
    SDL_SetError("Julia extension %s is not implemented for the vulkan backend", name);
    return NULL;
}
