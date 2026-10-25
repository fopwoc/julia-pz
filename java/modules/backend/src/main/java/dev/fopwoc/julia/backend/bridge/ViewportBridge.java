package dev.fopwoc.julia.backend.bridge;

import static org.lwjgl.opengl.GL11.GL_DEPTH_RANGE;
import static org.lwjgl.opengl.GL11.GL_SCISSOR_BOX;
import static org.lwjgl.opengl.GL11.GL_VIEWPORT;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import dev.fopwoc.julia.backend.Upstream;

/**
 * Indexed viewport state over SDL GPU's single viewport. Julia reports {@code GL_MAX_VIEWPORTS} as 1, so index 0 is
 * the ordinary viewport and any other index is out of range, as GL defines it.
 */
public final class ViewportBridge {
    private ViewportBridge() {}

    public static void glViewportIndexedf(int index, float x, float y, float width, float height) {
        requireFirst(index);
        GLStateManager.glViewport(Math.round(x), Math.round(y), Math.round(width), Math.round(height));
    }

    public static void glScissorIndexed(int index, int x, int y, int width, int height) {
        requireFirst(index);
        GLStateManager.glScissor(x, y, width, height);
    }

    public static void glDepthRangeIndexed(int index, double near, double far) {
        requireFirst(index);
        DepthRangeBridge.glDepthRange(near, far);
    }

    public static void glGetFloati_v(int parameter, int index, float[] values) {
        requireFirst(index);
        double[] state = indexed(parameter);
        for (int i = 0; i < Math.min(values.length, state.length); i++) values[i] = (float) state[i];
    }

    public static void glGetDoublei_v(int parameter, int index, double[] values) {
        requireFirst(index);
        double[] state = indexed(parameter);
        System.arraycopy(state, 0, values, 0, Math.min(values.length, state.length));
    }

    /** GLSM's indexed integer query stores one value; viewport and scissor boxes need all four. */
    public static boolean answersIntegers(int parameter) {
        return parameter == GL_VIEWPORT || parameter == GL_SCISSOR_BOX;
    }

    public static void glGetIntegeri_v(int parameter, int index, int[] values) {
        requireFirst(index);
        double[] state = indexed(parameter);
        for (int i = 0; i < Math.min(values.length, state.length); i++) values[i] = (int) state[i];
    }

    // Upstream's scalar queries do not cover viewport state, so the SDL context answers directly.
    private static double[] indexed(int parameter) {
        var state = Upstream.context();
        return switch (parameter) {
            case GL_VIEWPORT -> new double[] {state.viewportX, state.viewportY, state.viewportW, state.viewportH};
            case GL_SCISSOR_BOX -> new double[] {state.scissorX, state.scissorY, state.scissorW, state.scissorH};
            case GL_DEPTH_RANGE -> new double[] {state.viewportDepthNear, state.viewportDepthFar};
            default -> throw new UnsupportedOperationException("Indexed state query not implemented: " + parameter);
        };
    }

    private static void requireFirst(int index) {
        if (index != 0) throw new IllegalArgumentException("Viewport index exceeds GL_MAX_VIEWPORTS: " + index);
    }
}
