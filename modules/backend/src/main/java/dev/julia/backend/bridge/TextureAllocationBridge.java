package dev.julia.backend.bridge;

import static org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER_BINDING;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import java.nio.ByteBuffer;

/** Storage-only long-pointer overload; actual pointers and unpack-buffer offsets remain unsupported. */
public final class TextureAllocationBridge {
    private TextureAllocationBridge() {}

    public static void glTexImage3D(
            int target,
            int level,
            int internalFormat,
            int width,
            int height,
            int depth,
            int border,
            int format,
            int type,
            long pixels) {
        if (pixels != 0 || GLStateManager.glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING) != 0) {
            throw new UnsupportedOperationException("3D texture bridge supports null client storage only;"
                    + " pointers and pixel-unpack buffers require upload support");
        }
        GLStateManager.glTexImage3D(
                target, level, internalFormat, width, height, depth, border, format, type, (ByteBuffer) null);
    }
}
