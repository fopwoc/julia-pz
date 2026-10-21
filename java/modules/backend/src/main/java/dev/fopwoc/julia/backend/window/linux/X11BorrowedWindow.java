package dev.fopwoc.julia.backend.window.linux;

import static org.lwjgl.sdl.SDLProperties.SDL_SetNumberProperty;
import static org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_CREATE_X11_WINDOW_NUMBER;

import dev.fopwoc.julia.backend.utils.Sdl;
import dev.fopwoc.julia.backend.window.BorrowedWindow;
import dev.fopwoc.julia.backend.window.SdlBorrowedWindow;

/** SDL uses its own X connection; GLFW's connection and event selection remain untouched. */
public final class X11BorrowedWindow {
    private X11BorrowedWindow() {}

    public static BorrowedWindow borrow(long xid) {
        return SdlBorrowedWindow.create(
                "Wrap X11 window",
                properties -> Sdl.require(
                        SDL_SetNumberProperty(properties, SDL_PROP_WINDOW_CREATE_X11_WINDOW_NUMBER, xid),
                        "Set foreign X11 window"));
    }
}
