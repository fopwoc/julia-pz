package dev.fopwoc.julia.backend.window;

import static org.lwjgl.sdl.SDLProperties.*;
import static org.lwjgl.sdl.SDLVideo.*;

import dev.fopwoc.julia.backend.utils.Sdl;
import java.util.function.IntConsumer;

/** Owns only SDL's wrapper; the imported native window remains owned by GLFW. */
public final class SdlBorrowedWindow implements BorrowedWindow {
    private long handle;

    private SdlBorrowedWindow(long handle) {
        this.handle = handle;
    }

    public static SdlBorrowedWindow create(String label, IntConsumer configure) {
        int properties = SDL_CreateProperties();
        if (properties == 0) throw Sdl.failure("Create foreign window properties");
        try {
            Sdl.require(
                    SDL_SetBooleanProperty(properties, SDL_PROP_WINDOW_CREATE_VULKAN_BOOLEAN, true),
                    "Set Vulkan window property");
            configure.accept(properties);
            return new SdlBorrowedWindow(Sdl.require(SDL_CreateWindowWithProperties(properties), label));
        } finally {
            SDL_DestroyProperties(properties);
        }
    }

    @Override
    public long handle() {
        if (handle == 0) throw new IllegalStateException("Borrowed window is closed");
        return handle;
    }

    @Override
    public void close() {
        if (handle == 0) return;
        SDL_DestroyWindow(handle);
        handle = 0;
    }
}
