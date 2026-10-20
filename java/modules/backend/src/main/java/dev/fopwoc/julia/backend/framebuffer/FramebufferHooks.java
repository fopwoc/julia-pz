package dev.fopwoc.julia.backend.framebuffer;

import static org.lwjgl.sdl.SDLGPU.SDL_BeginGPURenderPass;
import static org.lwjgl.sdl.SDLGPU.SDL_GPU_TEXTUREFORMAT_INVALID;

import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import com.gtnewhorizons.angelica.sdlgpu.frame.FrameManager;
import com.gtnewhorizons.angelica.sdlgpu.resource.FboState;
import com.gtnewhorizons.angelica.sdlgpu.resource.ResourceManager;
import dev.fopwoc.julia.backend.BackendInternals;
import dev.fopwoc.julia.backend.platform.Platform;
import org.lwjgl.sdl.SDL_GPUColorTargetInfo;
import org.lwjgl.sdl.SDL_GPUDepthStencilTargetInfo;

public final class FramebufferHooks {
    private FramebufferHooks() {}

    public static long beginNative(
            long commands, SDL_GPUColorTargetInfo.Buffer colors, SDL_GPUDepthStencilTargetInfo depth) {
        if (colors != null)
            for (int i = colors.position(); i < colors.limit(); i++)
                if (colors.get(i).texture() == 0)
                    return Platform.current().beginSparseRenderPass(commands, colors, depth);
        return SDL_BeginGPURenderPass(commands, colors, depth);
    }

    public static long unusedTarget(ResourceManager resources) {
        var state = BackendInternals.context();
        var fbo = resources.getFbo(state.boundFboId);
        if (fbo != null) for (int output : fbo.drawBuffers) if (output < 0) return 0;
        return resources.getOrCreateDummyColorTarget();
    }

    public static void formats(ContextState state, FboState fbo) {
        if (fbo.cachedColorFormats == null || fbo.cachedColorFormats.length != fbo.drawBuffers.length)
            fbo.cachedColorFormats = new int[fbo.drawBuffers.length];
        for (int i = 0; i < fbo.drawBuffers.length; i++) {
            int attachment = fbo.drawBuffers[i];
            fbo.cachedColorFormats[i] = attachment < 0 ? SDL_GPU_TEXTUREFORMAT_INVALID : fbo.colorFormats[attachment];
        }
        fbo.cachedFormatsDirty = false;
        state.pipeline.setColorTargetFormats(fbo.cachedColorFormats);
        state.pipeline.setDrawBuffers(fbo.drawBuffers);
        state.pipeline.setFboDebug(state.boundFboId, fbo.colorAttachmentCount);
    }

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
