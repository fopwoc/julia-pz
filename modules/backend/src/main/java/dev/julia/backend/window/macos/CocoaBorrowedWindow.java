package dev.julia.backend.window.macos;

import static org.lwjgl.sdl.SDLProperties.SDL_CreateProperties;
import static org.lwjgl.sdl.SDLProperties.SDL_DestroyProperties;
import static org.lwjgl.sdl.SDLProperties.SDL_SetBooleanProperty;
import static org.lwjgl.sdl.SDLProperties.SDL_SetPointerProperty;
import static org.lwjgl.sdl.SDLVideo.SDL_CreateWindowWithProperties;
import static org.lwjgl.sdl.SDLVideo.SDL_DestroyWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_CREATE_COCOA_WINDOW_POINTER;
import static org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_CREATE_HIGH_PIXEL_DENSITY_BOOLEAN;
import static org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_CREATE_METAL_BOOLEAN;

import dev.julia.backend.utils.Sdl;
import dev.julia.backend.window.BorrowedWindow;

/** Wraps a GLFW-owned {@code NSWindow}; SDL's foreign-window listener changes are reverted immediately. */
public final class CocoaBorrowedWindow implements BorrowedWindow {
    private final CocoaWindowState original;
    private long handle;

    public CocoaBorrowedWindow(long cocoaWindow) {
        original = new CocoaWindowState(cocoaWindow);
        int properties = SDL_CreateProperties();
        if (properties == 0) throw Sdl.failure("Create window properties");
        try {
            Sdl.require(
                    SDL_SetPointerProperty(properties, SDL_PROP_WINDOW_CREATE_COCOA_WINDOW_POINTER, cocoaWindow)
                            && SDL_SetBooleanProperty(properties, SDL_PROP_WINDOW_CREATE_METAL_BOOLEAN, true)
                            && SDL_SetBooleanProperty(
                                    properties, SDL_PROP_WINDOW_CREATE_HIGH_PIXEL_DENSITY_BOOLEAN, true),
                    "Set foreign window properties");
            handle = Sdl.require(SDL_CreateWindowWithProperties(properties), "Wrap Cocoa window");
            original.restore();
            original.verify();
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        } finally {
            SDL_DestroyProperties(properties);
        }
    }

    @Override
    public long handle() {
        return handle;
    }

    @Override
    public void close() {
        try {
            if (handle != 0) {
                SDL_DestroyWindow(handle);
                handle = 0;
            }
        } finally {
            original.restore();
            original.verify();
        }
    }
}
