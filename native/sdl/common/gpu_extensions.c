/* Julia's public extension lookup is selected by the owning SDL GPU device, never by host OS. */
#include "SDL_internal.h"
#include <SDL3/SDL_gpu.h>
#include <SDL3/SDL_loadso.h>

#ifdef SDL_GPU_METAL
extern SDL_FunctionPointer JULIA_MetalGetExtension(const char *name);
#endif

__attribute__((visibility("default"))) SDL_FunctionPointer SDLCALL JULIA_SDL_GetExtension(SDL_GPUDevice *device, const char *name)
{
    if (!device || !name) {
        SDL_SetError("Julia extension lookup requires a GPU device and name");
        return NULL;
    }
    const char *driver = SDL_GetGPUDeviceDriver(device);
#ifdef SDL_GPU_METAL
    if (SDL_strcmp(driver, "metal") == 0) return JULIA_MetalGetExtension(name);
#endif
    SDL_SetError("Julia extension %s is not implemented for the %s backend", name, driver);
    return NULL;
}
