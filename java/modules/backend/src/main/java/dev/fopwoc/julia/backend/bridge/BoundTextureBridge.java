package dev.fopwoc.julia.backend.bridge;

import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;

import dev.fopwoc.julia.backend.BackendInternals;
import dev.fopwoc.julia.backend.texture.NamedTextureUploads;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import org.lwjgl.system.MemoryUtil;

/** Typed client uploads resolve the logical binding, independent of the upstream execution mirror. */
public final class BoundTextureBridge {
    private BoundTextureBridge() {}

    public static void glTexSubImage2D(
            int target, int level, int x, int y, int width, int height, int format, int type, ShortBuffer data) {
        if (data != null && !data.isDirect())
            throw new IllegalArgumentException("Texture upload requires direct client data");
        upload(target, level, x, y, width, height, format, type, data == null ? null : MemoryUtil.memByteBuffer(data));
    }

    public static void glTexSubImage2D(
            int target, int level, int x, int y, int width, int height, int format, int type, FloatBuffer data) {
        if (data != null && !data.isDirect())
            throw new IllegalArgumentException("Texture upload requires direct client data");
        upload(target, level, x, y, width, height, format, type, data == null ? null : MemoryUtil.memByteBuffer(data));
    }

    private static void upload(
            int target, int level, int x, int y, int width, int height, int format, int type, ByteBuffer data) {
        if (target != GL_TEXTURE_2D)
            throw new UnsupportedOperationException("Typed texture uploads currently require a 2D target");
        var state = BackendInternals.context();
        int name = BackendInternals.textureBindings().get(state, state.activeTextureUnit, target);
        NamedTextureUploads.upload(name, level, x, y, 0, width, height, 1, format, type, data, false);
    }
}
