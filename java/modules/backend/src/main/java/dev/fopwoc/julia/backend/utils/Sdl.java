package dev.fopwoc.julia.backend.utils;

import org.lwjgl.sdl.SDLError;

/** SDL failure reporting: every failed call carries SDL's thread-local error text. */
public final class Sdl {
    private Sdl() {}

    public static IllegalStateException failure(String operation) {
        return new IllegalStateException(operation + ": " + SDLError.SDL_GetError());
    }

    public static long require(long handle, String operation) {
        if (handle == 0) throw failure(operation);
        return handle;
    }

    public static void require(boolean succeeded, String operation) {
        if (!succeeded) throw failure(operation);
    }
}
