package dev.fopwoc.julia.backend.texture.macos;

import dev.fopwoc.julia.backend.utils.Sdl;
import org.lwjgl.sdl.SDL;
import org.lwjgl.system.JNI;

/** Native subresource views retain shared Metal storage; SDL owns retirement of each returned handle. */
public final class MetalTextureViews {
    private static final long CREATE = symbol("JULIA_SDL_CreateTextureView");

    static {
        if (JNI.invokeI(symbol("JULIA_SDL_TextureViewABI")) != 1)
            throw new IllegalStateException("Unexpected native texture-view ABI");
    }

    private MetalTextureViews() {}

    public static long create(long source, int type, int level, int levels, int layer, int layers) {
        return Sdl.require(
                JNI.invokePP(source, type, level, levels, layer, layers, CREATE), "Create Metal texture view");
    }

    private static long symbol(String name) {
        long address = SDL.getLibrary().getFunctionAddress(name);
        if (address == 0) throw new IllegalStateException("Missing native texture-view extension: " + name);
        return address;
    }
}
