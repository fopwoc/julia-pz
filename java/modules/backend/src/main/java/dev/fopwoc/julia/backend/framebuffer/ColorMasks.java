package dev.fopwoc.julia.backend.framebuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.GL_COLOR;
import static org.lwjgl.sdl.SDLGPU.*;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import dev.fopwoc.julia.backend.Upstream;
import dev.fopwoc.julia.backend.bridge.FramebufferClearBridge;
import java.util.Arrays;

/** Per-draw-output write masks; queued draws are drained before changing this non-captured state. */
public final class ColorMasks {
    private static final int ALL =
            SDL_GPU_COLORCOMPONENT_R | SDL_GPU_COLORCOMPONENT_G | SDL_GPU_COLORCOMPONENT_B | SDL_GPU_COLORCOMPONENT_A;
    private final int[] masks = new int[ContextState.MAX_COLOR_ATTACHMENTS];
    private boolean clearing;

    public ColorMasks() {
        Arrays.fill(masks, ALL);
    }

    public int mask(int output) {
        if (output < 0 || output >= masks.length)
            throw new IllegalArgumentException("Color-mask draw-buffer index out of range: " + output);
        return masks[output];
    }

    public long packed() {
        long value = 0;
        for (int i = 0; i < masks.length; i++) value |= (long) masks[i] << (i * 4);
        return value;
    }

    public boolean indexed() {
        for (int mask : masks) if (mask != masks[0]) return true;
        return false;
    }

    public void indexed(int output, boolean red, boolean green, boolean blue, boolean alpha) {
        int next = bits(red, green, blue, alpha);
        if (mask(output) == next) return;
        Upstream.flushTextureReferences();
        masks[output] = next;
        if (output == 0) GLStateManager.getColorMask().setAll(red, green, blue, alpha);
        changed();
    }

    public void global(boolean red, boolean green, boolean blue, boolean alpha) {
        int next = bits(red, green, blue, alpha);
        if (!indexed() && masks[0] == next) return;
        Upstream.flushTextureReferences();
        Arrays.fill(masks, next);
        changed();
    }

    private void changed() {
        var pipeline = Upstream.context().pipeline;
        pipeline.colorWriteMask = masks[0];
        pipeline.markOutputDirty();
    }

    /** Reuse the existing per-output clear path so fast full-attachment clears cannot ignore indexed masks. */
    public boolean clear(int bits) {
        if (clearing || (bits & GL_COLOR_BUFFER_BIT) == 0 || !indexed()) return false;
        var state = Upstream.context();
        var fbo = Upstream.resources().getFbo(state.boundFboId);
        int outputs = fbo == null ? 1 : fbo.drawBuffers.length;
        int previous = state.pipeline.colorWriteMask;
        clearing = true;
        try {
            for (int output = 0; output < outputs; output++) {
                if (masks[output] == 0) continue;
                state.pipeline.colorWriteMask = masks[output];
                FramebufferClearBridge.glClearBufferfv(
                        GL_COLOR, output, new float[] {state.clearR, state.clearG, state.clearB, state.clearA});
            }
            int rest = bits & ~GL_COLOR_BUFFER_BIT;
            if (rest != 0) Upstream.renderBackend().clear(rest);
        } finally {
            state.pipeline.colorWriteMask = previous;
            state.pipeline.markOutputDirty();
            clearing = false;
        }
        return true;
    }

    private static int bits(boolean red, boolean green, boolean blue, boolean alpha) {
        return (red ? SDL_GPU_COLORCOMPONENT_R : 0)
                | (green ? SDL_GPU_COLORCOMPONENT_G : 0)
                | (blue ? SDL_GPU_COLORCOMPONENT_B : 0)
                | (alpha ? SDL_GPU_COLORCOMPONENT_A : 0);
    }
}
