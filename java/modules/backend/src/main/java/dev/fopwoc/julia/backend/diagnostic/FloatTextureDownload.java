package dev.fopwoc.julia.backend.diagnostic;

import static org.lwjgl.sdl.SDLGPU.*;

import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import dev.fopwoc.julia.backend.Upstream;
import dev.fopwoc.julia.backend.utils.Sdl;
import java.nio.ByteBuffer;
import org.lwjgl.sdl.SDL_GPUTextureRegion;
import org.lwjgl.sdl.SDL_GPUTextureTransferInfo;
import org.lwjgl.sdl.SDL_GPUTransferBufferCreateInfo;
import org.lwjgl.system.MemoryStack;

/** Downloads native floating-point texels without passing them through RGBA8 pixel conversion. */
final class FloatTextureDownload {
    private FloatTextureDownload() {}

    static void read(int texture, int level, int width, int height, ByteBuffer pixels) {
        var resources = Upstream.resources();
        var frames = Upstream.frames();
        long device = SDLGPUGate.device().getDevice();
        try (var stack = MemoryStack.stackPush()) {
            long transfer = SDL_CreateGPUTransferBuffer(
                    device,
                    SDL_GPUTransferBufferCreateInfo.calloc(stack)
                            .usage(SDL_GPU_TRANSFERBUFFERUSAGE_DOWNLOAD)
                            .size(pixels.remaining()));
            if (transfer == 0) throw Sdl.failure("Create floating-point texture capture buffer");
            try {
                long copy = frames.ensureCopyPass();
                if (copy == 0) throw Sdl.failure("Begin floating-point texture capture");
                resources.flushBatchedUploads(copy);
                SDL_DownloadFromGPUTexture(
                        copy,
                        SDL_GPUTextureRegion.calloc(stack)
                                .texture(resources.getTextureHandle(texture))
                                .mip_level(level)
                                .w(width)
                                .h(height)
                                .d(1),
                        SDL_GPUTextureTransferInfo.calloc(stack)
                                .transfer_buffer(transfer)
                                .pixels_per_row(width)
                                .rows_per_layer(height));
                Upstream.submitPendingCommands();
                Sdl.require(SDL_WaitForGPUIdle(device), "Wait for floating-point texture capture");
                var state = Upstream.context();
                int draw = state.boundFboId, read = state.boundReadFboId;
                Upstream.renderBackend().invalidateStateMirror();
                state.boundFboId = draw;
                state.boundReadFboId = read;
                var mapped = SDL_MapGPUTransferBuffer(device, transfer, false, pixels.remaining());
                if (mapped == null) throw Sdl.failure("Map floating-point texture capture");
                try {
                    pixels.duplicate().put(mapped);
                } finally {
                    SDL_UnmapGPUTransferBuffer(device, transfer);
                }
            } finally {
                SDL_ReleaseGPUTransferBuffer(device, transfer);
            }
        }
    }
}
