/* Julia extension to SDL 3.4.12. Compiled inside SDL_gpu_metal.m; not an upstream SDL API. */
#ifdef SDL_GPU_METAL
/* Apple's Metal Performance HUD on one claimed window. Setting MTL_HUD_ENABLED is unreliable here: the game's
 * launcher can initialize Metal before Julia's agent runs, so the HUD is enabled on the presenting layer instead. */
__attribute__((visibility("default"))) bool SDLCALL JULIA_SDL_ShowDeveloperHud(SDL_GPUDevice *device, SDL_Window *window)
{
    @autoreleasepool {
        if (!device || SDL_strcmp(SDL_GetGPUDeviceDriver(device), "metal") != 0) {
            SDL_SetError("The developer HUD requires the SDL Metal backend");
            return false;
        }
        MetalWindowData *windowData = METAL_INTERNAL_FetchWindowData(window);
        if (!windowData || !windowData->layer) {
            SDL_SetError("The developer HUD requires a window claimed by the GPU device");
            return false;
        }
        if (@available(macOS 13.0, *)) {
            windowData->layer.developerHUDProperties = @{ @"mode" : @"default" };
            return true;
        }
        SDL_SetError("The Metal Performance HUD requires macOS 13 or later");
        return false;
    }
}
#endif
