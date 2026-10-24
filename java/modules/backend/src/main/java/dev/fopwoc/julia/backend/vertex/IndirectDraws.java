package dev.fopwoc.julia.backend.vertex;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.sdl.SDLGPU.SDL_DrawGPUPrimitivesIndirect;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import com.gtnewhorizons.angelica.sdlgpu.resource.FormatMap;
import dev.fopwoc.julia.backend.Upstream;

/** Commands stay GPU-resident; padded command streams use one native indirect call per record. */
public final class IndirectDraws {
    private IndirectDraws() {}

    public static void glMultiDrawArraysIndirect(int mode, long offset, int count, int stride) {
        var state = Upstream.context();
        if (count < 0 || stride < 0 || (stride & 3) != 0 || offset < 0 || (offset & 3) != 0)
            throw new IllegalArgumentException("Invalid indirect command range or alignment");
        if (count == 0) return;
        if (mode != GL_POINTS
                && mode != GL_LINES
                && mode != GL_LINE_STRIP
                && mode != GL_TRIANGLES
                && mode != GL_TRIANGLE_STRIP)
            throw new UnsupportedOperationException("Indirect primitive mode not implemented: " + mode);
        int step = stride == 0 ? 16 : stride;
        long size = Math.addExact(Math.multiplyExact((long) count - 1, step), 16);
        var resources = Upstream.resources();
        long buffer = resources.getBufferHandle(state.boundIndirectBuffer);
        if (buffer == 0
                || offset > resources.getBufferSize(state.boundIndirectBuffer)
                || size > resources.getBufferSize(state.boundIndirectBuffer) - offset
                || offset + size > 0x100000000L)
            throw new IllegalArgumentException("Indirect commands outside allocated GPU buffer");
        try (var _ = Upstream.drawLock()) {
            GLStateManager.preDraw(GLStateManager.ctx(), mode);
            draw(state, mode, buffer, offset, count, step);
        }
    }

    /** One indexed command; the multi-draw path already validates and draws it GPU-resident. */
    public static void glDrawElementsIndirect(int mode, int type, long offset) {
        GLStateManager.glMultiDrawElementsIndirect(mode, type, offset, 1, 0);
    }

    private static void draw(ContextState state, int mode, long buffer, long offset, int count, int stride) {
        if (state.rasterizerDiscard || state.boundProgramObj != null && state.boundProgramObj.vertexWriteReplay != null)
            throw new UnsupportedOperationException("Indirect draws with vertex side effects are not implemented");
        var frames = Upstream.frames();
        if (!frames.isFrameActive()) throw new IllegalStateException("Indirect draw requires an active frame");
        var pipeline = Upstream.pipeline();
        Upstream.drawDispatch().setPrimitiveTypeForDraw(state, FormatMap.mapPrimitiveType(mode));
        pipeline.ensureDrawRenderPass(state);
        if (!frames.isRenderPassActive() || !pipeline.applyPipelineAndState(state))
            throw new IllegalStateException("Cannot prepare indirect draw pipeline");
        long pass = frames.getRenderPass();
        if (stride == 16) SDL_DrawGPUPrimitivesIndirect(pass, buffer, (int) offset, count);
        else
            for (int i = 0; i < count; i++)
                SDL_DrawGPUPrimitivesIndirect(pass, buffer, (int) (offset + (long) i * stride), 1);
    }
}
