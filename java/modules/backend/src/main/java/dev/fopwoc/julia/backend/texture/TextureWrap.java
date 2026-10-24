package dev.fopwoc.julia.backend.texture;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL13.GL_CLAMP_TO_BORDER;

/**
 * SDL GPU samplers repeat, mirror or clamp to the edge; they have no border colour. Border and legacy clamp modes
 * become edge clamping, the closest match, rather than upstream's fallback to repeating.
 */
public final class TextureWrap {
    private static final System.Logger LOG = System.getLogger(TextureWrap.class.getName());
    private static boolean warned;

    private TextureWrap() {}

    public static int sampled(int parameter, int value) {
        if (parameter != GL_TEXTURE_WRAP_S && parameter != GL_TEXTURE_WRAP_T && parameter != GL_TEXTURE_WRAP_R)
            return value;
        if (value != GL_CLAMP_TO_BORDER && value != GL_CLAMP) return value;
        warnOnce();
        return GL_CLAMP_TO_EDGE;
    }

    /** {@code GL_TEXTURE_BORDER_COLOR} has nothing to set; the edge texel stands in for it. */
    public static void borderColor() {
        warnOnce();
    }

    private static void warnOnce() {
        if (warned) return;
        warned = true;
        LOG.log(
                System.Logger.Level.WARNING,
                "Border-colour texture wrapping is approximated by edge clamping; SDL GPU has no border colour");
    }
}
