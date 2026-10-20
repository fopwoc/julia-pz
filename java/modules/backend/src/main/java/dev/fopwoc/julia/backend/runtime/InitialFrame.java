package dev.fopwoc.julia.backend.runtime;

import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;

import com.gtnewhorizons.angelica.glsm.backend.RenderBackend;
import dev.fopwoc.julia.backend.Upstream;

/** Presents black before game assets load, consuming the active initialization frame. */
public final class InitialFrame {
    private InitialFrame() {}

    public static void present(RenderBackend backend) {
        var frames = Upstream.frames();
        if (!frames.isFrameActive()) throw new IllegalStateException("Initial frame is not active");
        var state = Upstream.context();
        float red = state.clearR, green = state.clearG, blue = state.clearB, alpha = state.clearA;
        try {
            backend.clearColor(0, 0, 0, 1);
            backend.clear(GL_COLOR_BUFFER_BIT);
            // Materialize the clear even though this frame contains no draw calls.
            frames.ensureFbo0RenderPass(frames.frame(), state);
            backend.onFrameEnd();
        } finally {
            backend.clearColor(red, green, blue, alpha);
        }
    }
}
