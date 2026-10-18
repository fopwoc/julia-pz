package dev.julia.probe.adapter;

import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import org.lwjgl.sdl.SDL_GPUTextureRegion;
import org.lwjgl.sdl.SDL_GPUTextureTransferInfo;
import org.lwjgl.system.MemoryStack;
import static org.lwjgl.sdl.SDLGPU.*;

/** RGBA-float constant clears of normalized RGBA8/BGRA8 textures, using bounded constant-color tiles. */
public final class TextureClearBridge {
    private TextureClearBridge() { }

    public static void glClearTexSubImage(int texture, int level, int x, int y, int z, int width, int height,
                                          int depth, int format, int type, float[] color) {
        if (format != 0x1908 || type != 0x1406) throw new UnsupportedOperationException("Clear bridge requires RGBA / FLOAT input");
        var resources = ResourceStateAccess.resources();
        var frames = ResourceStateAccess.frames();
        var meta = resources.getTextureMeta(texture);
        long handle = resources.getTextureHandle(texture);
        if (meta == null || handle == 0) throw new IllegalArgumentException("Texture has no allocated storage");
        boolean array = meta.glTarget() == 0x8C1A;
        if (!array && meta.glTarget() != 0x806F && meta.glTarget() != 0x0DE1) throw new UnsupportedOperationException("Unsupported texture clear target");
        boolean bgra = switch (meta.sdlFormat()) {
            case SDL_GPU_TEXTUREFORMAT_R8G8B8A8_UNORM -> false;
            case SDL_GPU_TEXTUREFORMAT_B8G8R8A8_UNORM -> true;
            default -> throw new UnsupportedOperationException("Clear bridge requires normalized four-byte color storage");
        };
        var region = new ClearRegion(level, x, y, z, width, height, depth);
        int shift = Math.clamp(level, 0, 30);
        region.validate(meta.levels(), Math.max(1, meta.width() >> shift), Math.max(1, meta.height() >> shift),
                array ? meta.depth() : Math.max(1, meta.depth() >> shift));
        byte[] pixel = ClearRegion.color(color, bgra);
        if (region.empty()) return;
        if (frames.getCommandBuffer() == 0) throw new IllegalStateException("Texture clear requires an active frame");
        ResourceStateAccess.clears().resolveDestinationForWrite(ShaderStateAccess.context(), handle, meta, level, x, y, z, width, height, false);
        long copy = frames.ensureCopyPass();
        if (copy == 0) throw new IllegalStateException("Cannot acquire texture-clear copy pass");
        resources.flushBatchedUploads(copy);
        int rows = region.tileRows();
        int bytes = Math.multiplyExact(Math.multiplyExact(width, rows), 4);
        long transfer = resources.acquireTransferBuffer(bytes);
        if (transfer == 0) throw new IllegalStateException("Cannot allocate clear transfer buffer");
        try (var stack = MemoryStack.stackPush()) {
            var device = SDLGPUGate.device().getDevice();
            var mapped = SDL_MapGPUTransferBuffer(device, transfer, true, resources.getTransferBufferMapSize(bytes));
            if (mapped == null || mapped.capacity() < bytes) {
                if (mapped != null) SDL_UnmapGPUTransferBuffer(device, transfer);
                throw new IllegalStateException("Cannot map texture-clear staging");
            }
            try { for (int i = 0; i < bytes; i++) mapped.put(i, pixel[i & 3]); }
            finally { SDL_UnmapGPUTransferBuffer(device, transfer); }
            var source = SDL_GPUTextureTransferInfo.calloc(stack).transfer_buffer(transfer).pixels_per_row(width).rows_per_layer(rows);
            var target = SDL_GPUTextureRegion.calloc(stack).texture(handle).mip_level(level).x(x).w(width).d(1);
            for (int layer = 0; layer < depth; layer++) {
                target.layer(array ? z + layer : 0).z(array ? 0 : z + layer);
                for (int row = 0; row < height; row += rows) {
                    target.y(y + row).h(Math.min(rows, height - row));
                    SDL_UploadToGPUTexture(copy, source, target, false);
                    frames.recordUploadCommands((long) width * target.h() * 4, 1);
                }
            }
            resources.markTextureContentDefined(handle);
            resources.markTextureWritten(handle);
        } finally { resources.returnTransferBuffer(transfer, bytes); }
    }
}
