package dev.julia.probe.adapter;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import org.lwjgl.sdl.SDL_GPUTextureRegion;
import org.lwjgl.sdl.SDL_GPUTextureTransferInfo;
import org.lwjgl.sdl.SDL_GPUTransferBufferCreateInfo;
import org.lwjgl.system.MemoryStack;
import static org.lwjgl.sdl.SDLGPU.*;

/** Diagnostic raw SDL layer readback; never captures a native window or game screen. */
final class ResourceReadback {
    private ResourceReadback() { }
    static byte[] layer(int texture, int layer) {
        var resources = ResourceStateAccess.resources();
        var frames = ResourceStateAccess.frames();
        var meta = resources.getTextureMeta(texture);
        int bytes = Math.multiplyExact(Math.multiplyExact(meta.width(), meta.height()), 4);
        long device = SDLGPUGate.device().getDevice();
        try (var stack = MemoryStack.stackPush()) {
            long download = SDL_CreateGPUTransferBuffer(device, SDL_GPUTransferBufferCreateInfo.calloc(stack)
                    .usage(SDL_GPU_TRANSFERBUFFERUSAGE_DOWNLOAD).size(bytes));
            if (download == 0) throw new IllegalStateException("Cannot allocate diagnostic download");
            try {
                long copy = frames.ensureCopyPass();
                if (copy == 0) throw new IllegalStateException("Cannot begin diagnostic download");
                resources.flushBatchedUploads(copy);
                SDL_DownloadFromGPUTexture(copy, SDL_GPUTextureRegion.calloc(stack)
                                .texture(resources.getTextureHandle(texture)).layer(layer).w(meta.width()).h(meta.height()).d(1),
                        SDL_GPUTextureTransferInfo.calloc(stack).transfer_buffer(download).pixels_per_row(meta.width()).rows_per_layer(meta.height()));
                frames.submitMidFrame();
                if (!SDL_WaitForGPUIdle(device)) throw new IllegalStateException("Cannot drain diagnostic download");
                BackendManager.RENDER_BACKEND.invalidateStateMirror();
                var mapped = SDL_MapGPUTransferBuffer(device, download, false, bytes);
                if (mapped == null) throw new IllegalStateException("Cannot map diagnostic download");
                try { var result = new byte[bytes]; mapped.get(result); return result; }
                finally { SDL_UnmapGPUTransferBuffer(device, download); }
            } finally { SDL_ReleaseGPUTransferBuffer(device, download); }
        }
    }
}
