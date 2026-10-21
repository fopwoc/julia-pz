package dev.fopwoc.julia.backend.window.macos;

import static org.lwjgl.sdl.SDLProperties.SDL_SetBooleanProperty;
import static org.lwjgl.sdl.SDLProperties.SDL_SetPointerProperty;
import static org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_CREATE_COCOA_WINDOW_POINTER;
import static org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_CREATE_HIGH_PIXEL_DENSITY_BOOLEAN;

import dev.fopwoc.julia.backend.utils.Sdl;
import dev.fopwoc.julia.backend.window.BorrowedWindow;
import dev.fopwoc.julia.backend.window.SdlBorrowedWindow;

/** Wraps a GLFW-owned {@code NSWindow}; SDL's foreign-window listener changes are reverted immediately. */
public final class CocoaBorrowedWindow implements BorrowedWindow {
    private final CocoaWindowState original;
    private SdlBorrowedWindow window;

    public CocoaBorrowedWindow(long cocoaWindow) {
        original = new CocoaWindowState(cocoaWindow);
        try {
            window = SdlBorrowedWindow.create(
                    "Wrap Cocoa window",
                    properties -> Sdl.require(
                            SDL_SetPointerProperty(properties, SDL_PROP_WINDOW_CREATE_COCOA_WINDOW_POINTER, cocoaWindow)
                                    && SDL_SetBooleanProperty(
                                            properties, SDL_PROP_WINDOW_CREATE_HIGH_PIXEL_DENSITY_BOOLEAN, true),
                            "Set foreign Cocoa window"));
            original.restore();
            original.verify();
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    @Override
    public long handle() {
        return window.handle();
    }

    @Override
    public void close() {
        try {
            if (window != null) {
                window.close();
                window = null;
            }
        } finally {
            original.restore();
            original.verify();
        }
    }
}
