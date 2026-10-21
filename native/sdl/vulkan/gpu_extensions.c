#ifdef SDL_GPU_VULKAN
/* Backend-local table; unknown features remain unavailable instead of borrowing Metal implementations. */
static int JULIA_VulkanSparseColorTargetsABI(void) { return 1; }
SDL_FunctionPointer JULIA_VulkanGetExtension(const char *name)
{
    if (SDL_strcmp(name, "JULIA_SDL_VertexInputABI") == 0) return (SDL_FunctionPointer)JULIA_VulkanVertexInputABI;
    if (SDL_strcmp(name, "JULIA_SDL_SamplerArgumentsABI") == 0) return (SDL_FunctionPointer)JULIA_VulkanSamplerArgumentsABI;
    if (SDL_strcmp(name, "JULIA_SDL_TextureViewABI") == 0) return (SDL_FunctionPointer)JULIA_VulkanTextureViewABI;
    if (SDL_strcmp(name, "JULIA_SDL_CreateTextureView") == 0) return (SDL_FunctionPointer)JULIA_VulkanCreateTextureView;
    if (SDL_strcmp(name, "JULIA_SDL_SparseColorTargetsABI") == 0) return (SDL_FunctionPointer)JULIA_VulkanSparseColorTargetsABI;
    if (SDL_strcmp(name, "JULIA_SDL_TimestampABI") == 0) return (SDL_FunctionPointer)JULIA_VulkanTimestampABI;
    if (SDL_strcmp(name, "JULIA_SDL_CreateTimestampClock") == 0) return (SDL_FunctionPointer)JULIA_VulkanCreateTimestampClock;
    if (SDL_strcmp(name, "JULIA_SDL_RecordTimestamp") == 0) return (SDL_FunctionPointer)JULIA_VulkanRecordTimestamp;
    if (SDL_strcmp(name, "JULIA_SDL_ReadTimestamp") == 0) return (SDL_FunctionPointer)JULIA_VulkanReadTimestamp;
    if (SDL_strcmp(name, "JULIA_SDL_ReleaseTimestamp") == 0) return (SDL_FunctionPointer)JULIA_VulkanReleaseTimestamp;
    if (SDL_strcmp(name, "JULIA_SDL_ReleaseTimestampClock") == 0) return (SDL_FunctionPointer)JULIA_VulkanReleaseTimestampClock;
    if (SDL_strcmp(name, "JULIA_SDL_VisibilityABI") == 0) return (SDL_FunctionPointer)JULIA_VulkanVisibilityABI;
    if (SDL_strcmp(name, "JULIA_SDL_BeginVisibilityQuery") == 0) return (SDL_FunctionPointer)JULIA_VulkanBeginVisibilityQuery;
    if (SDL_strcmp(name, "JULIA_SDL_EndVisibilityQuery") == 0) return (SDL_FunctionPointer)JULIA_VulkanEndVisibilityQuery;
    if (SDL_strcmp(name, "JULIA_SDL_ReadVisibilityQuery") == 0) return (SDL_FunctionPointer)JULIA_VulkanReadVisibilityQuery;
    if (SDL_strcmp(name, "JULIA_SDL_ReleaseVisibilityQuery") == 0) return (SDL_FunctionPointer)JULIA_VulkanReleaseVisibilityQuery;
    if (SDL_strcmp(name, "JULIA_SDL_BufferTextureABI") == 0) return (SDL_FunctionPointer)JULIA_VulkanBufferTextureABI;
    if (SDL_strcmp(name, "JULIA_SDL_BufferTextureLimit") == 0) return (SDL_FunctionPointer)JULIA_VulkanBufferTextureLimit;
    if (SDL_strcmp(name, "JULIA_SDL_BufferTextureAlignment") == 0) return (SDL_FunctionPointer)JULIA_VulkanBufferTextureAlignment;
    if (SDL_strcmp(name, "JULIA_SDL_BufferStorageIdentity") == 0) return (SDL_FunctionPointer)JULIA_VulkanBufferStorageIdentity;
    if (SDL_strcmp(name, "JULIA_SDL_CreateBufferTexture") == 0) return (SDL_FunctionPointer)JULIA_VulkanCreateBufferTexture;
    SDL_SetError("Julia extension %s is not implemented for the vulkan backend", name);
    return NULL;
}
#endif
