package dev.fopwoc.julia.backend.bridge;

import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_3D;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY;

import com.gtnewhorizons.angelica.sdlgpu.resource.ResourceManager;

/** A validated texel box at one mip level, shared by clears and copies. */
record TextureRegion(int level, int x, int y, int z, int width, int height, int depth) {
    private static final int MAX_TILE_BYTES = 65_536;
    private static final int MAX_ROW_BYTES = 64 * 1024 * 1024;

    static boolean isArray(int target) {
        return target == GL_TEXTURE_2D_ARRAY;
    }

    static boolean isSupported(int target) {
        return target == GL_TEXTURE_2D || target == GL_TEXTURE_2D_ARRAY || target == GL_TEXTURE_3D;
    }

    /** Array layers are never mip-reduced; volume depth is. */
    void validate(ResourceManager.TextureMeta meta) {
        int shift = Math.clamp(level, 0, 30);
        validate(
                meta.levels(),
                Math.max(1, meta.width() >> shift),
                Math.max(1, meta.height() >> shift),
                isArray(meta.glTarget()) ? meta.depth() : Math.max(1, meta.depth() >> shift));
    }

    void validate(int levels, int mipWidth, int mipHeight, int mipDepth) {
        boolean negative = level < 0 || x < 0 || y < 0 || z < 0 || width < 0 || height < 0 || depth < 0;
        if (negative
                || level >= levels
                || (long) x + width > mipWidth
                || (long) y + height > mipHeight
                || (long) z + depth > mipDepth) {
            throw new IllegalArgumentException("Texture region outside allocated storage");
        }
    }

    boolean empty() {
        return width == 0 || height == 0 || depth == 0;
    }

    /** Rows per bounded upload tile of four-byte texels. */
    int tileRows() {
        int rowBytes = Math.multiplyExact(width, 4);
        if (rowBytes <= 0 || rowBytes > MAX_ROW_BYTES) {
            throw new UnsupportedOperationException("Texture clear row is too large");
        }
        return Math.min(height, Math.max(1, MAX_TILE_BYTES / rowBytes));
    }

    /** Normalized RGBA8 encoding of a float clear color, in storage channel order. */
    static byte[] color(float[] values, boolean bgra) {
        if (values == null) return new byte[4];
        if (values.length < 4) throw new IllegalArgumentException("RGBA clear requires four components");
        byte[] result = new byte[4];
        for (int i = 0; i < 4; i++) {
            if (!Float.isFinite(values[i])) throw new UnsupportedOperationException("Non-finite clear component");
            result[i] = (byte) Math.round(Math.clamp(values[i], 0.0f, 1.0f) * 255.0f);
        }
        if (bgra) {
            byte red = result[0];
            result[0] = result[2];
            result[2] = red;
        }
        return result;
    }
}
