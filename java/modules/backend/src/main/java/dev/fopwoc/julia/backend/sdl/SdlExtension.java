package dev.fopwoc.julia.backend.sdl;

import org.lwjgl.sdl.SDL;
import org.lwjgl.system.JNI;

/**
 * Lookup of Julia's native extensions compiled into its own SDL build. Each extension exports the same C symbols for
 * every GPU backend that implements it, and a versioned ABI symbol that Java checks before the first call.
 */
public final class SdlExtension {
    private SdlExtension() {}

    public static long symbol(String name) {
        long address = SDL.getLibrary().getFunctionAddress(name);
        if (address == 0)
            throw new UnsupportedOperationException("Julia's SDL build does not export native extension " + name);
        return address;
    }

    public static void requireAbi(String name, int version) {
        int actual = JNI.invokeI(symbol(name));
        if (actual != version)
            throw new IllegalStateException(
                    "Unexpected native extension ABI " + name + ": " + actual + ", expected " + version);
    }
}
