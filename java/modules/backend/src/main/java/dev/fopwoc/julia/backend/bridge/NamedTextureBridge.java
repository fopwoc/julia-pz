package dev.fopwoc.julia.backend.bridge;

import dev.fopwoc.julia.backend.BackendInternals;
import dev.fopwoc.julia.backend.texture.NamedTextureUploads;
import dev.fopwoc.julia.backend.texture.TextureObjects;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import org.lwjgl.system.MemoryUtil;

/** Direct-state-access texture endpoints leave texture-unit bindings untouched. */
public final class NamedTextureBridge {
    private NamedTextureBridge() {}

    private static TextureObjects objects() {
        return BackendInternals.textureObjects();
    }

    public static int glCreateTextures(int target) {
        return objects().create(target);
    }

    public static void glCreateTextures(int target, IntBuffer names) {
        for (int i = names.position(); i < names.limit(); i++) names.put(i, glCreateTextures(target));
    }

    public static void glCreateTextures(int target, int[] names) {
        for (int i = 0; i < names.length; i++) names[i] = glCreateTextures(target);
    }

    public static void glTextureParameteri(int name, int parameter, int value) {
        objects().parameter(name, parameter, value);
    }

    public static void glTextureParameterf(int name, int parameter, float value) {
        objects().parameter(name, parameter, value);
    }

    public static void glTextureStorage2D(int name, int levels, int format, int width, int height) {
        objects().storage(name, levels, format, width, height, 1, false);
    }

    public static void glTextureStorage3D(int name, int levels, int format, int width, int height, int depth) {
        objects().storage(name, levels, format, width, height, depth, true);
    }

    public static void glTextureSubImage2D(
            int name, int level, int x, int y, int width, int height, int format, int type, ByteBuffer data) {
        NamedTextureUploads.upload(name, level, x, y, 0, width, height, 1, format, type, data, false);
    }

    public static void glTextureSubImage2D(
            int name, int level, int x, int y, int width, int height, int format, int type, IntBuffer data) {
        NamedTextureUploads.upload(
                name,
                level,
                x,
                y,
                0,
                width,
                height,
                1,
                format,
                type,
                (data == null ? null : MemoryUtil.memByteBuffer(data)),
                false);
    }

    public static void glTextureSubImage2D(
            int name, int level, int x, int y, int width, int height, int format, int type, FloatBuffer data) {
        NamedTextureUploads.upload(
                name,
                level,
                x,
                y,
                0,
                width,
                height,
                1,
                format,
                type,
                (data == null ? null : MemoryUtil.memByteBuffer(data)),
                false);
    }

    public static void glTextureSubImage2D(
            int name, int level, int x, int y, int width, int height, int format, int type, ShortBuffer data) {
        NamedTextureUploads.upload(
                name,
                level,
                x,
                y,
                0,
                width,
                height,
                1,
                format,
                type,
                (data == null ? null : MemoryUtil.memByteBuffer(data)),
                false);
    }

    public static void glTextureSubImage3D(
            int name,
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
        NamedTextureUploads.upload(name, level, x, y, z, width, height, depth, format, type, data, true);
    }

    public static void glTextureSubImage3D(
            int name,
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
                (data == null ? null : MemoryUtil.memByteBuffer(data)),
                true);
    }

    public static void glTextureSubImage3D(
            int name,
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
                (data == null ? null : MemoryUtil.memByteBuffer(data)),
                true);
    }

    public static void glTextureSubImage3D(
            int name,
            int level,
            int x,
            int y,
            int z,
            int width,
            int height,
            int depth,
            int format,
            int type,
            ShortBuffer data) {
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
                (data == null ? null : MemoryUtil.memByteBuffer(data)),
                true);
    }
}
