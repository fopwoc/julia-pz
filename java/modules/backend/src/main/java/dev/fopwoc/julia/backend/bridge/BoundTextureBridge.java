package dev.fopwoc.julia.backend.bridge;

import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_BORDER_COLOR;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_3D;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import dev.fopwoc.julia.backend.Subsystems;
import dev.fopwoc.julia.backend.Upstream;
import dev.fopwoc.julia.backend.texture.NamedTextureUploads;
import dev.fopwoc.julia.backend.texture.TextureWrap;
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
        var state = Upstream.context();
        int name = Subsystems.current().textureBindings().get(state, state.activeTextureUnit, target);
        Subsystems.current().textureObjects().storage(name, levels, format, width, height, depth, true);
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
        if (data != null && !data.isDirect())
            throw new IllegalArgumentException("Texture upload requires direct client data");
        volume(
                target,
                level,
                x,
                y,
                z,
                width,
                height,
                depth,
                format,
                type,
                data == null ? null : MemoryUtil.memByteBuffer(data));
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
            FloatBuffer data) {
        if (data != null && !data.isDirect())
            throw new IllegalArgumentException("Texture upload requires direct client data");
        volume(
                target,
                level,
                x,
                y,
                z,
                width,
                height,
                depth,
                format,
                type,
                data == null ? null : MemoryUtil.memByteBuffer(data));
    }

    public static void glTexParameterfv(int target, int parameter, float[] values) {
        if (values.length == 0) throw new IllegalArgumentException("Texture parameter needs a value");
        if (parameter == GL_TEXTURE_BORDER_COLOR) {
            if (values.length < 4) throw new IllegalArgumentException("Border colour needs four components");
            TextureWrap.borderColor();
        } else GLStateManager.glTexParameterf(target, parameter, values[0]);
    }

    public static void glGetTexImage(int target, int level, int format, int type, FloatBuffer pixels) {
        if (!pixels.isDirect()) throw new IllegalArgumentException("Texture readback requires direct client storage");
        GLStateManager.glGetTexImage(target, level, format, type, MemoryUtil.memByteBuffer(pixels));
    }

    // Array overloads carry typed pixels whose layout the format and type arguments already describe.
    public static void glTexImage2D(
            int target,
            int level,
            int format,
            int width,
            int height,
            int border,
            int pixelFormat,
            int type,
            float[] data) {
        var bytes = MemoryUtil.memAlloc(data.length * Float.BYTES);
        try {
            bytes.asFloatBuffer().put(data);
            GLStateManager.glTexImage2D(target, level, format, width, height, border, pixelFormat, type, bytes);
        } finally {
            MemoryUtil.memFree(bytes);
        }
    }

    public static void glTexImage2D(
            int target,
            int level,
            int format,
            int width,
            int height,
            int border,
            int pixelFormat,
            int type,
            int[] data) {
        var bytes = MemoryUtil.memAlloc(data.length * Integer.BYTES);
        try {
            bytes.asIntBuffer().put(data);
            GLStateManager.glTexImage2D(target, level, format, width, height, border, pixelFormat, type, bytes);
        } finally {
            MemoryUtil.memFree(bytes);
        }
    }

    public static void glTexImage3D(
            int target,
            int level,
            int format,
            int width,
            int height,
            int depth,
            int border,
            int pixelFormat,
            int type,
            float[] data) {
        var bytes = MemoryUtil.memAlloc(data.length * Float.BYTES);
        try {
            bytes.asFloatBuffer().put(data);
            GLStateManager.glTexImage3D(target, level, format, width, height, depth, border, pixelFormat, type, bytes);
        } finally {
            MemoryUtil.memFree(bytes);
        }
    }

    private static void volume(
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
            ByteBuffer data) {
        if (target != GL_TEXTURE_3D && target != GL_TEXTURE_2D_ARRAY)
            throw new UnsupportedOperationException("Typed volume uploads require a 3D or 2D-array target");
        var state = Upstream.context();
        int name = Subsystems.current().textureBindings().get(state, state.activeTextureUnit, target);
        NamedTextureUploads.upload(name, level, x, y, z, width, height, depth, format, type, data, true);
    }

    private static void upload(
            int target, int level, int x, int y, int width, int height, int format, int type, ByteBuffer data) {
        if (target != GL_TEXTURE_2D)
            throw new UnsupportedOperationException("Typed texture uploads currently require a 2D target");
        var state = Upstream.context();
        int name = Subsystems.current().textureBindings().get(state, state.activeTextureUnit, target);
        NamedTextureUploads.upload(name, level, x, y, 0, width, height, 1, format, type, data, false);
    }
}
