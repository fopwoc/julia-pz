package dev.fopwoc.julia.backend.sdl;

import dev.fopwoc.julia.backend.utils.Sdl;
import org.lwjgl.sdl.SDLGPU;
import org.lwjgl.sdl.SDL_GPUColorTargetInfo;
import org.lwjgl.sdl.SDL_GPUDepthStencilTargetInfo;
import org.lwjgl.system.JNI;

/** Render passes with truly absent color slots, which Julia's SDL build accepts instead of dummy textures. */
public final class SdlSparseTargets {
    static {
        SdlExtension.requireAbi("JULIA_SDL_SparseColorTargetsABI", 1);
    }

    private SdlSparseTargets() {}

    public static void requireSupported() {}

    public static long begin(long commands, SDL_GPUColorTargetInfo.Buffer colors, SDL_GPUDepthStencilTargetInfo depth) {
        if (commands == 0 || colors == null || colors.remaining() == 0 || colors.remaining() > 8)
            throw new IllegalArgumentException("Invalid sparse render-pass arguments");
        for (int i = colors.position(); i < colors.limit(); i++) {
            var color = colors.get(i);
            if (color.texture() != 0) SDL_GPUColorTargetInfo.validate(color.address());
            else if (color.resolve_texture() != 0 || color.cycle() || color.cycle_resolve_texture())
                throw new IllegalArgumentException("An absent color slot cannot cycle or resolve a texture");
        }
        if (depth != null) SDL_GPUDepthStencilTargetInfo.validate(depth.address());
        // LWJGL's generated struct check assumes every color texture is non-null. Keep all real
        // attachment validation and bypass only that assumption for the verified native extension.
        return Sdl.require(
                JNI.invokePPPP(
                        commands,
                        colors.address(),
                        colors.remaining(),
                        depth == null ? 0L : depth.address(),
                        SDLGPU.Functions.BeginGPURenderPass),
                "Begin sparse render pass");
    }
}
