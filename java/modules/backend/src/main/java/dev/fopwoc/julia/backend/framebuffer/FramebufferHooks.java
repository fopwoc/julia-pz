package dev.fopwoc.julia.backend.framebuffer;

import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import com.gtnewhorizons.angelica.sdlgpu.frame.FrameManager;
import com.gtnewhorizons.angelica.sdlgpu.resource.FboState;
import dev.fopwoc.julia.backend.BackendInternals;
import org.lwjgl.sdl.SDL_GPUColorTargetInfo;
import org.lwjgl.sdl.SDL_GPUDepthStencilTargetInfo;

public final class FramebufferHooks {
    private FramebufferHooks() {}

    public static void layout(FboState fbo) {
        BackendInternals.framebuffers().layout(fbo);
    }

    public static boolean sameSlices(int a, int b) {
        return BackendInternals.framebuffers().sameSlices(a, b);
    }

    public static void validateDraw(ContextState context) {
        BackendInternals.framebuffers().validateDraw(context);
    }

    public static long begin(
            FrameManager frames, SDL_GPUColorTargetInfo.Buffer colors, SDL_GPUDepthStencilTargetInfo depth) {
        return BackendInternals.framebuffers().begin(frames, colors, depth);
    }
}
