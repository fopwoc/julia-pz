/* Julia extension to SDL 3.4.12. Compiled inside SDL_gpu_metal.m; not an upstream SDL API. */
#ifdef SDL_GPU_METAL
__attribute__((visibility("default"))) int SDLCALL JULIA_SDL_VertexInputABI(void) { return 1; }

static NSUInteger JULIA_MetalVertexStepRate(SDL_PropertiesID properties, Uint32 slot)
{
    char key[64];
    SDL_snprintf(key, sizeof(key), "julia.gpu.vertex_divisor.%u", slot);
    return (NSUInteger)SDL_GetNumberProperty(properties, key, 1);
}
#endif
