/* Julia extension to SDL 3.4.12. Compiled inside SDL_gpu_metal.m; not an upstream SDL API. */
#ifdef SDL_GPU_METAL
/* The layer property configures an already enabled HUD. Enable the runtime before SDL video/device creation;
 * use only process-local settings so this switch cannot persist into another game launch. */
__attribute__((visibility("default"))) bool SDLCALL JULIA_SDL_PrepareDeveloperHud(void)
{
    @autoreleasepool {
        if (@available(macOS 13.0, *)) {
            if (setenv("MTL_HUD_ENABLED", "1", 1) != 0) {
                SDL_SetError("Cannot enable the Metal HUD environment");
                return false;
            }
            NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
            NSMutableDictionary *arguments = [[defaults volatileDomainForName:NSArgumentDomain] mutableCopy];
            if (!arguments) arguments = [NSMutableDictionary dictionary];
            /* Apple documents different preference names in the original tech talk and current HUD guide. */
            arguments[@"MetalForceHudEnabled"] = @YES;
            arguments[@"MetalHUDForceEnabled"] = @YES;
            [defaults setVolatileDomain:arguments forName:NSArgumentDomain];
            return true;
        }
        SDL_SetError("The Metal Performance HUD requires macOS 13 or later");
        return false;
    }
}

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
