/* Julia extension: sparse color formats and render-pass targets in the pinned SDL Metal backend. */
#ifdef SDL_GPU_METAL
__attribute__((visibility("default"))) int SDLCALL JULIA_SDL_SparseColorTargetsABI(void) { return 1; }
#endif
