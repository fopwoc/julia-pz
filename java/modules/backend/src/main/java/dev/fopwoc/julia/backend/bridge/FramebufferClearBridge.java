package dev.fopwoc.julia.backend.bridge;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import com.gtnewhorizons.angelica.sdlgpu.resource.PixelOps;
import dev.fopwoc.julia.backend.Subsystems;
import dev.fopwoc.julia.backend.Upstream;
import java.nio.FloatBuffer;
import java.util.Arrays;

/** Scoped per-draw-buffer clears reuse the masked/scissored clear path without changing public clear state. */
public final class FramebufferClearBridge {
    private FramebufferClearBridge() {}

    public static void glClearBufferfv(int buffer, int drawbuffer, float[] values) {
        int count = components(buffer, drawbuffer);
        if (values.length < count) throw new IllegalArgumentException("Framebuffer clear values are too short");
        clear(
                buffer,
                drawbuffer,
                values[0],
                count == 4 ? values[1] : 0,
                count == 4 ? values[2] : 0,
                count == 4 ? values[3] : 0,
                false);
    }

    public static void glClearBufferfv(int buffer, int drawbuffer, FloatBuffer values) {
        int count = components(buffer, drawbuffer);
        if (!values.isDirect() || values.remaining() < count)
            throw new IllegalArgumentException("Framebuffer clear requires enough direct float values");
        int at = values.position();
        clear(
                buffer,
                drawbuffer,
                values.get(at),
                count == 4 ? values.get(at + 1) : 0,
                count == 4 ? values.get(at + 2) : 0,
                count == 4 ? values.get(at + 3) : 0,
                false);
    }

    public static void glClearBufferuiv(int buffer, int drawbuffer, int[] values) {
        integer(buffer, drawbuffer, values);
    }

    public static void glClearBufferiv(int buffer, int drawbuffer, int[] values) {
        integer(buffer, drawbuffer, values);
    }

    /**
     * SDL passes clear colours as floats, which backends read as values or as bits for integer targets; zero means
     * the same either way, so only zero integer clears are portable.
     */
    private static void integer(int buffer, int drawbuffer, int[] values) {
        if (buffer != GL_COLOR) throw new IllegalArgumentException("Integer framebuffer clear requires GL_COLOR");
        components(buffer, drawbuffer);
        if (values.length < 4) throw new IllegalArgumentException("Framebuffer clear values are too short");
        for (int i = 0; i < 4; i++)
            if (values[i] != 0)
                throw new UnsupportedOperationException("Non-zero integer framebuffer clears are not implemented");
        clear(buffer, drawbuffer, 0, 0, 0, 0, true);
    }

    private static int components(int buffer, int drawbuffer) {
        if (buffer != GL_COLOR && buffer != GL_DEPTH)
            throw new IllegalArgumentException("Floating-point framebuffer clear requires GL_COLOR or GL_DEPTH");
        if (drawbuffer < 0 || (buffer == GL_DEPTH ? drawbuffer != 0 : drawbuffer >= ContextState.MAX_COLOR_ATTACHMENTS))
            throw new IllegalArgumentException("Framebuffer clear draw-buffer index out of range: " + drawbuffer);
        return buffer == GL_COLOR ? 4 : 1;
    }

    private static void clear(
            int buffer, int drawbuffer, float red, float green, float blue, float alpha, boolean integer) {
        var state = Upstream.context();
        var backend = BackendManager.RENDER_BACKEND;
        var framebuffers = Subsystems.current().framebuffers();
        framebuffers.validateDraw(state);
        if (Upstream.frames().getCommandBuffer() == 0)
            throw new IllegalStateException("Framebuffer clear requires an active frame");
        if (buffer == GL_DEPTH) {
            float previous = state.depthClearValue;
            try {
                backend.clearDepth(Math.clamp(red, 0.0f, 1.0f));
                backend.clear(GL_DEPTH_BUFFER_BIT);
            } finally {
                backend.clearDepth(previous);
            }
            return;
        }
        var fbo = Upstream.resources().getFbo(state.boundFboId);
        if (fbo == null && state.boundFboId != 0)
            throw new IllegalStateException("Framebuffer clear target does not exist");
        if (fbo == null ? drawbuffer != 0 : drawbuffer >= fbo.drawBuffers.length || fbo.drawBuffers[drawbuffer] < 0)
            return;
        int[] previous = fbo == null
                ? null
                : Arrays.stream(fbo.drawBuffers)
                        .map(index -> index < 0 ? GL_NONE : GL_COLOR_ATTACHMENT0 + index)
                        .toArray();
        boolean integerTarget =
                fbo != null && PixelOps.isSdlFormatInteger(fbo.colorFormats[fbo.drawBuffers[drawbuffer]]);
        if (integerTarget != integer)
            throw new UnsupportedOperationException(
                    integer
                            ? "Integer clear of a non-integer color attachment"
                            : "Floating-point clear of an integer color attachment");
        float oldRed = state.clearR, oldGreen = state.clearG, oldBlue = state.clearB, oldAlpha = state.clearA;
        try {
            if (previous != null) {
                int[] selected = new int[previous.length];
                Arrays.fill(selected, GL_NONE);
                selected[drawbuffer] = previous[drawbuffer];
                framebuffers.drawBuffers(state.boundFboId, selected);
            }
            backend.clearColor(red, green, blue, alpha);
            backend.clear(GL_COLOR_BUFFER_BIT);
        } finally {
            backend.clearColor(oldRed, oldGreen, oldBlue, oldAlpha);
            if (previous != null) framebuffers.drawBuffers(state.boundFboId, previous);
        }
    }
}
