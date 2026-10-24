package dev.fopwoc.julia.backend.framebuffer;

import static org.lwjgl.opengl.GL14.*;

import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import com.gtnewhorizons.angelica.sdlgpu.resource.FormatMap;
import dev.fopwoc.julia.backend.Upstream;
import java.util.Arrays;

/**
 * Per-draw-buffer blend equations. Upstream's pipeline keeps one colour/alpha pair, so Julia holds a full set only
 * while some output differs from it; pipeline creation and its cache key read the set through
 * {@link BlendEquationHooks}. Queued draws are drained before changing this non-captured state.
 */
public final class BlendEquations {
    private final int[] color = new int[ContextState.MAX_COLOR_ATTACHMENTS];
    private final int[] alpha = new int[ContextState.MAX_COLOR_ATTACHMENTS];
    private boolean indexed;

    /** {@code glBlendEquationSeparatei}: GL equation enums for one draw-buffer output. */
    public void indexed(int output, int colorMode, int alphaMode) {
        if (output < 0 || output >= color.length)
            throw new IllegalArgumentException("Blend draw-buffer index out of range: " + output);
        int colorOp = op(colorMode), alphaOp = op(alphaMode);
        var pipeline = Upstream.context().pipeline;
        if (!indexed) {
            if (colorOp == pipeline.colorBlendOp && alphaOp == pipeline.alphaBlendOp) return;
            Arrays.fill(color, pipeline.colorBlendOp);
            Arrays.fill(alpha, pipeline.alphaBlendOp);
        } else if (color[output] == colorOp && alpha[output] == alphaOp) return;
        Upstream.flushTextureReferences();
        color[output] = colorOp;
        alpha[output] = alphaOp;
        // Collapsing back to upstream's pair keeps GLSM's cached global equation truthful.
        indexed = false;
        for (int i = 0; i < color.length; i++)
            if (color[i] != pipeline.colorBlendOp || alpha[i] != pipeline.alphaBlendOp) indexed = true;
        pipeline.markOutputDirty();
    }

    /** A global equation replaces every output's; upstream then holds the only pair. */
    public void global() {
        if (!indexed) return;
        Upstream.flushTextureReferences();
        indexed = false;
        Upstream.context().pipeline.markOutputDirty();
    }

    public boolean indexed() {
        return indexed;
    }

    public int color(int output, int upstream) {
        return indexed ? color[output] : upstream;
    }

    public int alpha(int output, int upstream) {
        return indexed ? alpha[output] : upstream;
    }

    /** Every output's pair, four bits per op; SDL has five blend ops. */
    public long packed() {
        long value = 0;
        for (int i = 0; i < color.length; i++) value |= (long) (color[i] | alpha[i] << 4) << (i * 8);
        return value;
    }

    private static int op(int mode) {
        return switch (mode) {
            case GL_FUNC_ADD, GL_FUNC_SUBTRACT, GL_FUNC_REVERSE_SUBTRACT, GL_MIN, GL_MAX -> FormatMap.mapBlendOp(mode);
            default -> throw new IllegalArgumentException("Invalid blend equation: " + mode);
        };
    }
}
