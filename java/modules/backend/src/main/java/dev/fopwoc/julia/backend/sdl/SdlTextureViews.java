package dev.fopwoc.julia.backend.sdl;

import dev.fopwoc.julia.backend.utils.Sdl;
import org.lwjgl.system.JNI;

/** Native subresource views share their source's GPU storage; SDL owns retirement of each returned handle. */
public final class SdlTextureViews {
    private static final long CREATE = SdlExtension.symbol("JULIA_SDL_CreateTextureView");

    static {
        SdlExtension.requireAbi("JULIA_SDL_TextureViewABI", 1);
    }

    private SdlTextureViews() {}

    public static long create(long source, int type, int level, int levels, int layer, int layers) {
        return Sdl.require(
                JNI.invokePP(source, type, level, levels, layer, layers, CREATE), "Create native texture view");
    }
}
