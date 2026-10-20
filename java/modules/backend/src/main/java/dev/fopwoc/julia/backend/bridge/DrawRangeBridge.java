package dev.fopwoc.julia.backend.bridge;

import static org.lwjgl.opengl.GL11.GL_QUADS;

import com.gtnewhorizons.angelica.glsm.GLStateManager;

/** Preserves GLSM's quad conversion for the ranged EBO overload used by PZ's map renderer. */
public final class DrawRangeBridge {
    private DrawRangeBridge() {}

    public static void glDrawRangeElements(int mode, int start, int end, int count, int type, long indices) {
        if (mode == GL_QUADS) {
            // The valid index range does not change the vertices drawn. GLSM's ordinary indexed
            // path also preserves EBO bindings, display-list recording, and quad triangulation.
            GLStateManager.glDrawElements(mode, count, type, indices);
        } else {
            GLStateManager.glDrawRangeElements(mode, start, end, count, type, indices);
        }
    }
}
