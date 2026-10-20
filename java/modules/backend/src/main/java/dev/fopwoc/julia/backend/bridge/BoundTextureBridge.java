package dev.fopwoc.julia.backend.bridge;

import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_3D;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY;

import dev.fopwoc.julia.backend.BackendInternals;
import dev.fopwoc.julia.backend.texture.NamedTextureUploads;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import org.lwjgl.system.MemoryUtil;

/** Bound texture operations resolve the logical binding, independent of the upstream execution mirror. */
public final class BoundTextureBridge {
    private BoundTextureBridge() {}

    public static void glTexStorage3D(int target, int levels, int format, int width, int height, int depth) {
        if (target != GL_TEXTURE_3D && target != GL_TEXTURE_2D_ARRAY)
            throw new UnsupportedOperationException("Volume storage requires a 3D or 2D-array target");
        var state = BackendInternals.context();
        int name = BackendInternals.textureBindings().get(state, state.activeTextureUnit, target);
        BackendInternals.textureObjects().storage(name, levels, format, width, height, depth, true);
    }

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

    public static void glTexSubImage3D(
            int target,
            int level,
            int x,
            int y,
            int z,
            int width,
            int height,
            int depth,
            int format,
            int type,
            IntBuffer data) {
        if (target != GL_TEXTURE_3D && target != GL_TEXTURE_2D_ARRAY)
            throw new UnsupportedOperationException("Integer volume uploads require a 3D or 2D-array target");
        if (data != null && !data.isDirect())
            throw new IllegalArgumentException("Texture upload requires direct client data");
        var state = BackendInternals.context();
        int name = BackendInternals.textureBindings().get(state, state.activeTextureUnit, target);
        NamedTextureUploads.upload(
                name,
                level,
                x,
                y,
                z,
                width,
                height,
                depth,
                format,
                type,
                data == null ? null : MemoryUtil.memByteBuffer(data),
                true);
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
