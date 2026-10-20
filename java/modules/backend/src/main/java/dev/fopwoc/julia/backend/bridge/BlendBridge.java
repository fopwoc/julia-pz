package dev.fopwoc.julia.backend.bridge;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL14.*;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;

/** Indexed factors enter GLSM's existing per-output path so later global blend calls invalidate correctly. */
public final class BlendBridge {
    private BlendBridge() {}

    public static void glBlendFunci(int buffer, int source, int destination) {
        if (buffer < 0 || buffer >= ContextState.MAX_COLOR_ATTACHMENTS)
            throw new IllegalArgumentException("Blend draw-buffer index out of range: " + buffer);
        requireFactor(source);
        requireFactor(destination);
        GLStateManager.glBlendFuncSeparatei(buffer, source, destination, source, destination);
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
