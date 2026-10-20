/* Julia extension to SDL 3.4.12. Compiled inside SDL_gpu_metal.m; not an upstream SDL API. */
#ifdef SDL_GPU_METAL
__attribute__((visibility("default"))) int SDLCALL JULIA_SDL_VertexInputABI(void) { return 2; }

static NSUInteger JULIA_MetalVertexStepRate(SDL_PropertiesID properties, Uint32 slot)
{
    char key[64];
    SDL_snprintf(key, sizeof(key), "julia.gpu.vertex_divisor.%u", slot);
    /* Negative selects a constant; GLuint divisors retain their nonnegative 64-bit representation. */
    Sint64 rate = SDL_GetNumberProperty(properties, key, 1);
    return rate < 0 ? 0 : (NSUInteger)rate;
}
static MTLVertexStepFunction JULIA_MetalVertexStepFunction(SDL_PropertiesID properties, Uint32 slot, SDL_GPUVertexInputRate rate)
{
    char key[64];
    SDL_snprintf(key, sizeof(key), "julia.gpu.vertex_divisor.%u", slot);
    return SDL_GetNumberProperty(properties, key, 1) < 0 ? MTLVertexStepFunctionConstant : SDLToMetal_StepFunction[rate];
}
#endif
