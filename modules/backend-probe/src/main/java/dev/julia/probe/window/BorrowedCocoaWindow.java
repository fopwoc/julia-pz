package dev.julia.probe.window;

import org.lwjgl.sdl.SDLError;
import static org.lwjgl.sdl.SDLProperties.*;
import static org.lwjgl.sdl.SDLVideo.*;

/** Owns the SDL wrapper only; the native window, content view, and input remain GLFW-owned. */
final class BorrowedCocoaWindow implements AutoCloseable {
    private final CocoaWindowState original;
    private long handle;

    BorrowedCocoaWindow(long cocoaWindow) {
        original = new CocoaWindowState(cocoaWindow);
        int properties = SDL_CreateProperties();
        if (properties == 0) throw failure("Create window properties");
        try {
            if (!SDL_SetPointerProperty(properties, SDL_PROP_WINDOW_CREATE_COCOA_WINDOW_POINTER, cocoaWindow)
                    || !SDL_SetBooleanProperty(properties, SDL_PROP_WINDOW_CREATE_METAL_BOOLEAN, true)
                    || !SDL_SetBooleanProperty(properties, SDL_PROP_WINDOW_CREATE_HIGH_PIXEL_DENSITY_BOOLEAN, true)) {
                throw failure("Set foreign window properties");
            }
            handle = SDL_CreateWindowWithProperties(properties);
            if (handle == 0) throw failure("Wrap Cocoa window");
            original.restore();
            original.verify();
        } catch (RuntimeException | Error exception) {
            close();
            throw exception;
        } finally {
            SDL_DestroyProperties(properties);
        }
    }

    long handle() { return handle; }

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

    private static IllegalStateException failure(String operation) {
        return new IllegalStateException(operation + ": " + SDLError.SDL_GetError());
    }
}
