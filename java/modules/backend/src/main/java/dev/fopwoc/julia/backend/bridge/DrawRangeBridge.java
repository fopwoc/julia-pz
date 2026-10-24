package dev.fopwoc.julia.backend.bridge;

import static org.lwjgl.opengl.GL11.GL_QUADS;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import java.nio.IntBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;

/** Preserves GLSM's quad conversion for the ranged EBO overload used by PZ's map renderer. */
public final class DrawRangeBridge {
    private DrawRangeBridge() {}

    /**
     * {@code glMultiDrawElementsBaseVertex} after its caller unpacked the LWJGL pointer buffer, which cannot cross into
     * the isolated runtime, into the address and count of its offsets.
     */
    public static void glMultiDrawElementsBaseVertex(
            int mode, IntBuffer counts, int type, long offsets, int draws, IntBuffer baseVertices) {
        if (draws < 0 || counts.remaining() < draws || baseVertices.remaining() < draws)
            throw new IllegalArgumentException("Multi-draw arrays are shorter than the draw count");
        for (int i = 0; i < draws; i++) {
            int count = counts.get(counts.position() + i);
            if (count == 0) continue;
            GLStateManager.glDrawElementsBaseVertex(
                    mode,
                    count,
                    type,
                    MemoryUtil.memGetAddress(offsets + (long) i * Pointer.POINTER_SIZE),
                    baseVertices.get(baseVertices.position() + i));
        }
    }

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
