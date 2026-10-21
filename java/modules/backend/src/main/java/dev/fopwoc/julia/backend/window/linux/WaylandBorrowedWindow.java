package dev.fopwoc.julia.backend.window.linux;

import static org.lwjgl.sdl.SDLProperties.*;
import static org.lwjgl.sdl.SDLVideo.*;

import dev.fopwoc.julia.backend.utils.Sdl;
import dev.fopwoc.julia.backend.window.BorrowedWindow;
import dev.fopwoc.julia.backend.window.SdlBorrowedWindow;
import dev.fopwoc.julia.runtime.ForeignWindow;

/** External Wayland surfaces keep GLFW's role, listeners, scaling objects and resize ownership. */
public final class WaylandBorrowedWindow implements BorrowedWindow {
    private final ForeignWindow.Wayland foreign;
    private final SdlBorrowedWindow window;
    private long dimensions;

    public WaylandBorrowedWindow(ForeignWindow.Wayland foreign) {
        this.foreign = foreign;
        dimensions = foreign.pixels().getAsLong();
        window = SdlBorrowedWindow.create(
                "Wrap Wayland surface",
                properties -> Sdl.require(
                        SDL_SetPointerProperty(
                                        properties,
                                        SDL_PROP_WINDOW_CREATE_WAYLAND_WL_SURFACE_POINTER,
                                        foreign.surface())
                                && SDL_SetBooleanProperty(
                                        properties, SDL_PROP_WINDOW_CREATE_WAYLAND_SURFACE_ROLE_CUSTOM_BOOLEAN, true)
                                && SDL_SetBooleanProperty(
                                        properties, SDL_PROP_WINDOW_CREATE_HIGH_PIXEL_DENSITY_BOOLEAN, false)
                                && SDL_SetNumberProperty(
                                        properties, SDL_PROP_WINDOW_CREATE_WIDTH_NUMBER, dimensions >>> 32)
                                && SDL_SetNumberProperty(
                                        properties, SDL_PROP_WINDOW_CREATE_HEIGHT_NUMBER, dimensions & 0xffffffffL),
                        "Set foreign Wayland surface"));
    }

    @Override
    public long handle() {
        long next = foreign.pixels().getAsLong();
        if (next != dimensions && (next >>> 32) > 0 && (int) next > 0) {
            Sdl.require(SDL_SetWindowSize(window.handle(), (int) (next >>> 32), (int) next), "Resize Wayland wrapper");
            dimensions = next;
        }
        return window.handle();
    }

    @Override
    public void close() {
        window.close();
    }
}
