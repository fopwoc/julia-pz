package dev.fopwoc.julia.probe.adapter;

import static org.lwjgl.sdl.SDLGPU.*;

import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import dev.fopwoc.julia.backend.BackendInternals;
import org.lwjgl.sdl.SDL_GPUTextureRegion;
import org.lwjgl.sdl.SDL_GPUTextureTransferInfo;
import org.lwjgl.sdl.SDL_GPUTransferBufferCreateInfo;
import org.lwjgl.system.MemoryStack;

/** Queues a download without submitting or waiting; the caller must establish GPU completion before reading. */
public final class FencedReadback implements AutoCloseable {
    private final long device = SDLGPUGate.device().getDevice();
    private final long transfer;
    private final int bytes;

    public FencedReadback(int texture) {
        var resources = BackendInternals.resources();
        var meta = resources.getTextureMeta(texture);
        bytes = Math.multiplyExact(Math.multiplyExact(meta.width(), meta.height()), 4);
        BackendInternals.timestampCommandBuffer();
        try (var stack = MemoryStack.stackPush()) {
            transfer = SDL_CreateGPUTransferBuffer(
                    device,
                    SDL_GPUTransferBufferCreateInfo.calloc(stack)
                            .usage(SDL_GPU_TRANSFERBUFFERUSAGE_DOWNLOAD)
                            .size(bytes));
            if (transfer == 0) throw new IllegalStateException("Cannot allocate fenced download");
            long copy = BackendInternals.frames().ensureCopyPass();
            if (copy == 0) {
                SDL_ReleaseGPUTransferBuffer(device, transfer);
                throw new IllegalStateException("Cannot begin fenced download");
            }
            resources.flushBatchedUploads(copy);
            SDL_DownloadFromGPUTexture(
                    copy,
                    SDL_GPUTextureRegion.calloc(stack)
                            .texture(resources.getTextureHandle(texture))
                            .w(meta.width())
                            .h(meta.height())
                            .d(1),
                    SDL_GPUTextureTransferInfo.calloc(stack)
                            .transfer_buffer(transfer)
                            .pixels_per_row(meta.width())
                            .rows_per_layer(meta.height()));
        }
    }

    public byte[] read() {
        var mapped = SDL_MapGPUTransferBuffer(device, transfer, false, bytes);
        if (mapped == null) throw new IllegalStateException("Cannot map fenced download");
        try {
            var result = new byte[bytes];
            mapped.get(result);
            return result;
        } finally {
            SDL_UnmapGPUTransferBuffer(device, transfer);
        }
    }

    @Override
    public void close() {
        SDL_ReleaseGPUTransferBuffer(device, transfer);
    }
}
