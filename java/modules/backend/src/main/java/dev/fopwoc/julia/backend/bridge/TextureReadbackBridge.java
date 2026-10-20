package dev.fopwoc.julia.backend.bridge;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.sdl.SDLGPU.SDL_GPU_TEXTUREFORMAT_R8G8B8A8_UNORM;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import dev.fopwoc.julia.backend.Subsystems;
import dev.fopwoc.julia.backend.Upstream;
import java.nio.ByteBuffer;
import java.util.Objects;

/** Checked named 2D RGBA8 downloads reuse the upstream pixel-pack/readback implementation. */
public final class TextureReadbackBridge {
    private TextureReadbackBridge() {}

    public static void glGetTextureImage(int texture, int level, int format, int type, ByteBuffer result) {
        Objects.requireNonNull(result);
        if (!result.isDirect() || result.isReadOnly())
            throw new IllegalArgumentException("Texture readback requires writable direct storage");
        var state = Upstream.context();
        if (state.boundPixelPackBuffer != 0)
            throw new IllegalStateException("Client texture readback requires no pixel-pack buffer");
        long needed = required(texture, level, format, type);
        if (result.remaining() < needed)
            throw new IllegalArgumentException("Texture readback destination is too small");
        if (needed == 0) return;
        Upstream.withReadbackTexture(
                texture,
                () -> BackendManager.RENDER_BACKEND.getTexImage(
                        GL_TEXTURE_2D, level, format, type, result.duplicate()));
    }

    public static void glGetTextureImage(int texture, int level, int format, int type, int capacity, long offset) {
        if (capacity < 0 || offset < 0) throw new IllegalArgumentException("Negative texture readback range");
        long needed = required(texture, level, format, type);
        var state = Upstream.context();
        int buffer = state.boundPixelPackBuffer;
        if (buffer == 0) throw new IllegalStateException("Texture readback offset requires a pixel-pack buffer");
        long size = Upstream.resources().getBufferSize(buffer);
        if (needed > capacity || offset > size || needed > size - offset)
            throw new IllegalArgumentException("Texture readback exceeds its declared or allocated capacity");
        if (needed == 0) return;
        if (Upstream.frames().getCommandBuffer() == 0)
            throw new IllegalStateException("Pixel-pack readback requires an active frame");
        Upstream.withReadbackTexture(
                texture, () -> BackendManager.RENDER_BACKEND.getTexImage(GL_TEXTURE_2D, level, format, type, offset));
    }

    private static long required(int texture, int level, int format, int type) {
        if (format != GL_RGBA || type != GL_UNSIGNED_BYTE)
            throw new UnsupportedOperationException("Named texture readback currently requires RGBA unsigned bytes");
        var objects = Subsystems.current().textureObjects();
        if (objects.target(texture) != GL_TEXTURE_2D)
            throw new UnsupportedOperationException("Named texture readback currently requires a 2D texture");
        int width = objects.levelParameter(texture, level, GL_TEXTURE_WIDTH);
        int height = objects.levelParameter(texture, level, GL_TEXTURE_HEIGHT);
        if (width == 0 || height == 0) return 0;
        var meta = Upstream.resources().getTextureMeta(texture);
        if (meta.sdlFormat() != SDL_GPU_TEXTUREFORMAT_R8G8B8A8_UNORM)
            throw new UnsupportedOperationException("Named texture readback currently requires native RGBA8 storage");
        var store = Upstream.context().pixelStore;
        int alignment = store.packAlignment;
        if ((alignment != 1 && alignment != 2 && alignment != 4 && alignment != 8)
                || store.packRowLength < 0
                || store.packSkipPixels < 0
                || store.packSkipRows < 0) throw new IllegalArgumentException("Invalid pixel-pack layout");
        long row = Math.multiplyExact((long) (store.packRowLength == 0 ? width : store.packRowLength), 4);
        row = Math.multiplyExact(Math.addExact(row, alignment - 1) / alignment, alignment);
        long needed = Math.addExact(
                Math.multiplyExact(Math.addExact((long) store.packSkipRows, height - 1), row),
                Math.multiplyExact(Math.addExact((long) store.packSkipPixels, width), 4));
        if (needed > Integer.MAX_VALUE || (long) width * height * 4 > Integer.MAX_VALUE - 16)
            throw new UnsupportedOperationException("Texture readback exceeds the supported staging range");
        return needed;
    }
}
