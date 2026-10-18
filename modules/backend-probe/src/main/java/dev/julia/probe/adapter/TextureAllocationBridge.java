package dev.julia.probe.adapter;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import java.nio.ByteBuffer;

/** Storage-only long-pointer overload; actual pointers and unpack-buffer offsets remain unsupported. */
public final class TextureAllocationBridge {
    private static final int PIXEL_UNPACK_BUFFER_BINDING = 0x88EF;
    private TextureAllocationBridge() { }

    public static void glTexImage3D(int target, int level, int internalFormat, int width, int height,
                                    int depth, int border, int format, int type, long pixels) {
        if (pixels != 0 || GLStateManager.glGetInteger(PIXEL_UNPACK_BUFFER_BINDING) != 0) {
            throw new UnsupportedOperationException("3D texture bridge supports null client storage only; pointers and pixel-unpack buffers require upload support");
        }
        GLStateManager.glTexImage3D(target, level, internalFormat, width, height, depth, border, format, type, (ByteBuffer) null);
    }
}
