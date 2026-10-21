/* Backend-local function table, included after the Metal extensions in SDL_gpu_metal.m. */
#ifdef SDL_GPU_METAL
SDL_FunctionPointer JULIA_MetalGetExtension(const char *name)
{
    if (SDL_strcmp(name, "JULIA_SDL_BeginVisibilityQuery") == 0) return (SDL_FunctionPointer)JULIA_SDL_BeginVisibilityQuery;
    if (SDL_strcmp(name, "JULIA_SDL_BufferStorageIdentity") == 0) return (SDL_FunctionPointer)JULIA_SDL_BufferStorageIdentity;
    if (SDL_strcmp(name, "JULIA_SDL_BufferTextureABI") == 0) return (SDL_FunctionPointer)JULIA_SDL_BufferTextureABI;
    if (SDL_strcmp(name, "JULIA_SDL_BufferTextureAlignment") == 0) return (SDL_FunctionPointer)JULIA_SDL_BufferTextureAlignment;
    if (SDL_strcmp(name, "JULIA_SDL_BufferTextureLimit") == 0) return (SDL_FunctionPointer)JULIA_SDL_BufferTextureLimit;
    if (SDL_strcmp(name, "JULIA_SDL_CreateBufferTexture") == 0) return (SDL_FunctionPointer)JULIA_SDL_CreateBufferTexture;
    if (SDL_strcmp(name, "JULIA_SDL_CreateTextureView") == 0) return (SDL_FunctionPointer)JULIA_SDL_CreateTextureView;
    if (SDL_strcmp(name, "JULIA_SDL_CreateTimestampClock") == 0) return (SDL_FunctionPointer)JULIA_SDL_CreateTimestampClock;
    if (SDL_strcmp(name, "JULIA_SDL_EndVisibilityQuery") == 0) return (SDL_FunctionPointer)JULIA_SDL_EndVisibilityQuery;
    if (SDL_strcmp(name, "JULIA_SDL_ReadTimestamp") == 0) return (SDL_FunctionPointer)JULIA_SDL_ReadTimestamp;
    if (SDL_strcmp(name, "JULIA_SDL_ReadVisibilityQuery") == 0) return (SDL_FunctionPointer)JULIA_SDL_ReadVisibilityQuery;
    if (SDL_strcmp(name, "JULIA_SDL_RecordTimestamp") == 0) return (SDL_FunctionPointer)JULIA_SDL_RecordTimestamp;
    if (SDL_strcmp(name, "JULIA_SDL_ReleaseTimestamp") == 0) return (SDL_FunctionPointer)JULIA_SDL_ReleaseTimestamp;
    if (SDL_strcmp(name, "JULIA_SDL_ReleaseTimestampClock") == 0) return (SDL_FunctionPointer)JULIA_SDL_ReleaseTimestampClock;
    if (SDL_strcmp(name, "JULIA_SDL_ReleaseVisibilityQuery") == 0) return (SDL_FunctionPointer)JULIA_SDL_ReleaseVisibilityQuery;
    if (SDL_strcmp(name, "JULIA_SDL_SamplerArgumentsABI") == 0) return (SDL_FunctionPointer)JULIA_SDL_SamplerArgumentsABI;
    if (SDL_strcmp(name, "JULIA_SDL_SparseColorTargetsABI") == 0) return (SDL_FunctionPointer)JULIA_SDL_SparseColorTargetsABI;
    if (SDL_strcmp(name, "JULIA_SDL_TextureViewABI") == 0) return (SDL_FunctionPointer)JULIA_SDL_TextureViewABI;
    if (SDL_strcmp(name, "JULIA_SDL_TimestampABI") == 0) return (SDL_FunctionPointer)JULIA_SDL_TimestampABI;
    if (SDL_strcmp(name, "JULIA_SDL_VertexInputABI") == 0) return (SDL_FunctionPointer)JULIA_SDL_VertexInputABI;
    if (SDL_strcmp(name, "JULIA_SDL_VisibilityABI") == 0) return (SDL_FunctionPointer)JULIA_SDL_VisibilityABI;
    SDL_SetError("Unknown Julia Metal extension: %s", name);
    return NULL;
}
#endif
