package dev.fopwoc.julia.probe.adapter;

import static org.lwjgl.sdl.SDLGPU.SDL_CreateGPUTransferBuffer;
import static org.lwjgl.sdl.SDLGPU.SDL_DownloadFromGPUTexture;
import static org.lwjgl.sdl.SDLGPU.SDL_GPU_TRANSFERBUFFERUSAGE_DOWNLOAD;
import static org.lwjgl.sdl.SDLGPU.SDL_MapGPUTransferBuffer;
import static org.lwjgl.sdl.SDLGPU.SDL_ReleaseGPUTransferBuffer;
import static org.lwjgl.sdl.SDLGPU.SDL_UnmapGPUTransferBuffer;
import static org.lwjgl.sdl.SDLGPU.SDL_WaitForGPUIdle;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import dev.fopwoc.julia.backend.BackendInternals;
import org.lwjgl.sdl.SDL_GPUTextureRegion;
import org.lwjgl.sdl.SDL_GPUTextureTransferInfo;
import org.lwjgl.sdl.SDL_GPUTransferBufferCreateInfo;
import org.lwjgl.system.MemoryStack;

/** Diagnostic raw SDL readback of one texture layer; never captures a native window or game screen. */
public final class ResourceReadback {
    private ResourceReadback() {}

    public static byte[] layer(int texture, int layer) {
        var resources = BackendInternals.resources();
        var frames = BackendInternals.frames();
        var meta = resources.getTextureMeta(texture);
        int bytes = Math.multiplyExact(Math.multiplyExact(meta.width(), meta.height()), 4);
        long device = SDLGPUGate.device().getDevice();
        try (var stack = MemoryStack.stackPush()) {
            var downloadInfo = SDL_GPUTransferBufferCreateInfo.calloc(stack)
                    .usage(SDL_GPU_TRANSFERBUFFERUSAGE_DOWNLOAD)
                    .size(bytes);
            long download = SDL_CreateGPUTransferBuffer(device, downloadInfo);
            if (download == 0) throw new IllegalStateException("Cannot allocate diagnostic download");
            try {
                long copy = frames.ensureCopyPass();
                if (copy == 0) throw new IllegalStateException("Cannot begin diagnostic download");
                resources.flushBatchedUploads(copy);
                SDL_DownloadFromGPUTexture(
                        copy,
                        SDL_GPUTextureRegion.calloc(stack)
                                .texture(resources.getTextureHandle(texture))
                                .layer(layer)
                                .w(meta.width())
                                .h(meta.height())
                                .d(1),
                        SDL_GPUTextureTransferInfo.calloc(stack)
                                .transfer_buffer(download)
                                .pixels_per_row(meta.width())
                                .rows_per_layer(meta.height()));
                BackendInternals.submitPendingCommands();
                if (!SDL_WaitForGPUIdle(device)) throw new IllegalStateException("Cannot drain diagnostic download");
                var state = BackendInternals.context();
                int drawFbo = state.boundFboId, readFbo = state.boundReadFboId;
                BackendManager.RENDER_BACKEND.invalidateStateMirror();
                state.boundFboId = drawFbo;
                state.boundReadFboId = readFbo;
                var mapped = SDL_MapGPUTransferBuffer(device, download, false, bytes);
                if (mapped == null) throw new IllegalStateException("Cannot map diagnostic download");
                try {
                    var result = new byte[bytes];
                    mapped.get(result);
                    return result;
                } finally {
                    SDL_UnmapGPUTransferBuffer(device, download);
                }
            } finally {
                SDL_ReleaseGPUTransferBuffer(device, download);
            }
        }
    }
}
