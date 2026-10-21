package dev.fopwoc.julia.backend.bridge;

import static org.lwjgl.opengl.GL11.GL_COLOR_WRITEMASK;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import dev.fopwoc.julia.backend.Subsystems;
import java.nio.ByteBuffer;

/** Indexed color-mask entry points use output slots, independent of attachment routing. */
public final class ColorMaskBridge {
    private ColorMaskBridge() {}

    public static void glColorMaski(int output, boolean red, boolean green, boolean blue, boolean alpha) {
        Subsystems.current().colorMasks().indexed(output, red, green, blue, alpha);
    }

    public static void glGetBooleani_v(int parameter, int output, ByteBuffer values) {
        if (parameter != GL_COLOR_WRITEMASK) {
            GLStateManager.glGetBooleani_v(parameter, output, values);
            return;
        }
        if (values.remaining() < 4) throw new IllegalArgumentException("Color-mask query requires four bytes");
        int mask = Subsystems.current().colorMasks().mask(output);
        for (int i = 0; i < 4; i++) values.put(values.position() + i, (byte) ((mask >> i) & 1));
    }
}
