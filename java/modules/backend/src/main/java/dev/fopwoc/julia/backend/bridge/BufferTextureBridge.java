package dev.fopwoc.julia.backend.bridge;

import static org.lwjgl.opengl.GL31.GL_TEXTURE_BUFFER;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import dev.fopwoc.julia.backend.BackendInternals;

/** Keeps buffer-texture bindings separate from the upstream single ordinary texture binding per unit. */
public final class BufferTextureBridge {
    private BufferTextureBridge() {}

    public static void glBindTexture(int target, int name) {
        var buffers = BackendInternals.bufferTextures();
        if (target == GL_TEXTURE_BUFFER) buffers.bindTexture(BackendInternals.context().activeTextureUnit, name);
        else {
            if (name != 0 && buffers.contains(name))
                throw new IllegalArgumentException("Buffer texture rebound to a different target");
            GLStateManager.glBindTexture(target, name);
        }
    }

    public static void glTexBuffer(int target, int format, int buffer) {
        attach(target, format, buffer, 0, 0, true);
    }

    public static void glTexBufferRange(int target, int format, int buffer, long offset, long size) {
        attach(target, format, buffer, offset, size, false);
    }

    private static void attach(int target, int format, int buffer, long offset, long size, boolean whole) {
        if (target != GL_TEXTURE_BUFFER)
            throw new IllegalArgumentException("Buffer texture target must be GL_TEXTURE_BUFFER");
        var textures = BackendInternals.bufferTextures();
        textures.attach(
                textures.boundTexture(BackendInternals.context().activeTextureUnit),
                format,
                buffer,
                offset,
                size,
                whole);
    }
}
