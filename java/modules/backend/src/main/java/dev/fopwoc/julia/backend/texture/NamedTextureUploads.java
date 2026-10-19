package dev.fopwoc.julia.backend.texture;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.sdl.SDLGPU.*;

import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import dev.fopwoc.julia.backend.BackendInternals;
import java.nio.ByteBuffer;
import org.lwjgl.sdl.SDL_GPUTextureRegion;
import org.lwjgl.sdl.SDL_GPUTextureTransferInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Copies client bytes into queued GPU staging, preserving draw/upload order and untouched array layers. */
public final class NamedTextureUploads {
    private NamedTextureUploads() {}

    public static void upload(
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
            ByteBuffer data,
            boolean volume) {
        int target = BackendInternals.textureObjects().target(name);
        if (volume ? target != GL_TEXTURE_3D && target != GL_TEXTURE_2D_ARRAY : target != GL_TEXTURE_2D)
            throw new IllegalArgumentException("Named upload dimensionality does not match texture target");
        var resources = BackendInternals.resources();
        var meta = resources.getTextureMeta(name);
        if (meta == null || resources.getTextureHandle(name) == 0)
            throw new IllegalArgumentException("Named upload requires allocated storage");
        int shift = Math.clamp(level, 0, 30);
        int layers = target == GL_TEXTURE_2D_ARRAY ? meta.depth() : Math.max(1, meta.depth() >> shift);
        if (level < 0
                || level >= meta.levels()
                || x < 0
                || y < 0
                || z < 0
                || width < 0
                || height < 0
                || depth < 0
                || (long) x + width > Math.max(1, meta.width() >> shift)
                || (long) y + height > Math.max(1, meta.height() >> shift)
                || (long) z + depth > layers)
            throw new IllegalArgumentException("Named upload region outside allocated storage");
        var transfer = TextureUploadFormat.transfer(meta.glFormat());
        boolean bgra = transfer.format() == GL_RGBA && format == GL_BGRA && type == GL_UNSIGNED_BYTE;
        if ((!bgra && format != transfer.format()) || type != transfer.type())
            throw new UnsupportedOperationException("Named texture upload conversion not implemented: storage="
                    + meta.glFormat() + " format=" + format + " type=" + type);
        if (width == 0 || height == 0 || depth == 0) return;
        var state = BackendInternals.context();
        if (state.boundPixelUnpackBuffer != 0)
            throw new UnsupportedOperationException("Client upload with a pixel-unpack buffer bound");
        if (data == null || !data.isDirect())
            throw new IllegalArgumentException("Named upload requires direct client data");
        var unpack = BackendInternals.volumeUnpack();
        UnpackLayout layout = UnpackLayout.of(
                width,
                height,
                depth,
                transfer.bytes(),
                state.pixelStore,
                volume ? unpack.imageHeight : 0,
                volume ? unpack.skipImages : 0);
        if (data.remaining() < layout.requiredBytes())
            throw new IllegalArgumentException(
                    "Named upload client data is too short: needs " + layout.requiredBytes());
        int outBytes = meta.glFormat() == GL_RGB8 ? 4 : transfer.bytes();
        int rowBytes = Math.multiplyExact(width, outBytes),
                sliceBytes = Math.multiplyExact(rowBytes, height),
                size = Math.multiplyExact(sliceBytes, depth);
        long device = SDLGPUGate.device().getDevice();
        long staging = resources.acquireTransferBuffer(size);
        if (staging == 0) throw new IllegalStateException("Cannot allocate named upload staging");
        try (var stack = MemoryStack.stackPush()) {
            var mapped = SDL_MapGPUTransferBuffer(device, staging, true, resources.getTransferBufferMapSize(size));
            if (mapped == null) throw new IllegalStateException("Cannot map named upload staging");
            try {
                int componentBytes = type == GL_FLOAT || type == GL_INT || type == GL_UNSIGNED_INT
                        ? 4
                        : type == GL_SHORT || type == GL_UNSIGNED_SHORT || type == GL_HALF_FLOAT ? 2 : 1;
                for (int layer = 0; layer < depth; layer++)
                    for (int row = 0; row < height; row++) {
                        int source = Math.toIntExact(data.position()
                                + layout.start()
                                + layer * layout.imageStride()
                                + row * layout.rowStride());
                        int destination = layer * sliceBytes + row * rowBytes;
                        if (!bgra && meta.glFormat() != GL_RGB8 && !unpack.swapBytes) {
                            MemoryUtil.memCopy(
                                    MemoryUtil.memAddress(data, source),
                                    MemoryUtil.memAddress(mapped, destination),
                                    rowBytes);
                            continue;
                        }
                        for (int pixel = 0; pixel < width; pixel++)
                            for (int channel = 0; channel < outBytes; channel++) {
                                if (meta.glFormat() == GL_RGB8 && channel == 3) {
                                    mapped.put(destination + pixel * outBytes + channel, (byte) 255);
                                    continue;
                                }
                                int c = bgra && channel < 3 ? 2 - channel : channel;
                                if (unpack.swapBytes && componentBytes > 1)
                                    c = (channel / componentBytes) * componentBytes
                                            + componentBytes
                                            - 1
                                            - channel % componentBytes;
                                mapped.put(
                                        destination + pixel * outBytes + channel,
                                        data.get(source + pixel * transfer.bytes() + c));
                            }
                    }
            } finally {
                SDL_UnmapGPUTransferBuffer(device, staging);
            }
            long handle = resources.getTextureHandle(name);
            var frames = BackendInternals.frames();
            if (frames.getCommandBuffer() == 0)
                throw new IllegalStateException("Named upload requires an active frame");
            var clears = BackendInternals.clears();
            clears.resolveDestinationForWrite(state, handle, meta, level, x, y, z, width, height, false);
            long copy = frames.ensureCopyPass();
            if (copy == 0) throw new IllegalStateException("Cannot acquire named upload copy pass");
            resources.flushBatchedUploads(copy);
            var source = SDL_GPUTextureTransferInfo.calloc(stack)
                    .transfer_buffer(staging)
                    .pixels_per_row(width)
                    .rows_per_layer(height);
            var destination = SDL_GPUTextureRegion.calloc(stack)
                    .texture(handle)
                    .mip_level(level)
                    .x(x)
                    .y(y)
                    .w(width)
                    .h(height)
                    .d(1);
            for (int layer = 0; layer < depth; layer++) {
                source.offset(layer * sliceBytes);
                destination
                        .layer(target == GL_TEXTURE_2D_ARRAY ? z + layer : 0)
                        .z(target == GL_TEXTURE_2D_ARRAY ? 0 : z + layer);
                SDL_UploadToGPUTexture(copy, source, destination, false);
            }
            frames.recordUploadCommands(size, depth);
            resources.markTextureContentDefined(handle);
            resources.markTextureWritten(handle);
        } finally {
            resources.returnTransferBuffer(staging, size);
        }
    }
}
