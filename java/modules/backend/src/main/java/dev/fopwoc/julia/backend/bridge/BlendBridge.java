package dev.fopwoc.julia.backend.bridge;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL14.*;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import dev.fopwoc.julia.backend.Subsystems;
import dev.fopwoc.julia.backend.Upstream;

/** Indexed blend state enters GLSM's per-output path so subsequent global calls invalidate correctly. */
public final class BlendBridge {
    private BlendBridge() {}

    public static void glBlendFunci(int buffer, int source, int destination) {
        glBlendFuncSeparatei(buffer, source, destination, source, destination);
    }

    public static void glBlendFuncSeparatei(
            int buffer, int source, int destination, int sourceAlpha, int destinationAlpha) {
        if (buffer < 0 || buffer >= ContextState.MAX_COLOR_ATTACHMENTS)
            throw new IllegalArgumentException("Blend draw-buffer index out of range: " + buffer);
        for (int factor : new int[] {source, destination, sourceAlpha, destinationAlpha}) requireFactor(factor);
        Upstream.flushTextureReferences();
        GLStateManager.glBlendFuncSeparatei(buffer, source, destination, sourceAlpha, destinationAlpha);
    }

    public static void glBlendEquationi(int buffer, int mode) {
        Subsystems.current().blendEquations().indexed(buffer, mode, mode);
    }

    public static void glBlendEquationSeparatei(int buffer, int color, int alpha) {
        Subsystems.current().blendEquations().indexed(buffer, color, alpha);
    }

    public static void glEnablei(int capability, int index) {
        requireIndexed(capability, index);
        Upstream.flushTextureReferences();
        GLStateManager.glEnablei(capability, index);
    }

    public static void glDisablei(int capability, int index) {
        requireIndexed(capability, index);
        Upstream.flushTextureReferences();
        GLStateManager.glDisablei(capability, index);
    }

    public static boolean glIsEnabledi(int capability, int index) {
        requireIndexed(capability, index);
        var state = Upstream.context();
        return capability == GL_BLEND ? state.pipeline.blendEnabledPerDrawBuffer[index] : state.scissorEnabled;
    }

    private static void requireIndexed(int capability, int index) {
        int limit = switch (capability) {
            case GL_BLEND -> ContextState.MAX_COLOR_ATTACHMENTS;
            case GL_SCISSOR_TEST -> 1;
            default -> throw new UnsupportedOperationException("Indexed capability not implemented: " + capability);
        };
        if (index < 0 || index >= limit)
            throw new IllegalArgumentException("Indexed capability index out of range: " + index);
    }

    private static void requireFactor(int factor) {
        switch (factor) {
            case GL_ZERO,
                    GL_ONE,
                    GL_SRC_COLOR,
                    GL_ONE_MINUS_SRC_COLOR,
                    GL_DST_COLOR,
                    GL_ONE_MINUS_DST_COLOR,
                    GL_SRC_ALPHA,
                    GL_ONE_MINUS_SRC_ALPHA,
                    GL_DST_ALPHA,
                    GL_ONE_MINUS_DST_ALPHA,
                    GL_SRC_ALPHA_SATURATE,
                    GL_CONSTANT_COLOR,
                    GL_ONE_MINUS_CONSTANT_COLOR -> {}
            default -> throw new UnsupportedOperationException("Indexed blend factor not implemented: " + factor);
        }
    }
}
