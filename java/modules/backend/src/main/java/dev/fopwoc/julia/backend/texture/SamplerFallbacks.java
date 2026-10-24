package dev.fopwoc.julia.backend.texture;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_3D;
import static org.lwjgl.opengl.GL13.GL_TEXTURE_CUBE_MAP;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY;
import static org.lwjgl.opengl.GL40.GL_TEXTURE_CUBE_MAP_ARRAY;
import static org.lwjgl.sdl.SDLGPU.*;
import static org.lwjgl.util.spvc.Spvc.*;

import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import dev.fopwoc.julia.backend.utils.Sdl;
import java.nio.ByteOrder;
import org.lwjgl.sdl.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Incomplete GL samplers read black with alpha one; native descriptors still need the declared image dimension. */
public final class SamplerFallbacks implements AutoCloseable {
    private static final int FIRST = -16;
    private static final int[] TYPES = {
        SDL_GPU_TEXTURETYPE_2D,
        SDL_GPU_TEXTURETYPE_3D,
        SDL_GPU_TEXTURETYPE_2D_ARRAY,
        SDL_GPU_TEXTURETYPE_CUBE,
        SDL_GPU_TEXTURETYPE_CUBE_ARRAY
    };
    private static final int[] FORMATS = {
        SDL_GPU_TEXTUREFORMAT_R8G8B8A8_UNORM,
        SDL_GPU_TEXTUREFORMAT_R32G32B32A32_INT,
        SDL_GPU_TEXTUREFORMAT_R32G32B32A32_UINT
    };
    private final long[] textures = new long[TYPES.length * FORMATS.length];

    public static int name(int target, int scalar) {
        int shape = switch (target) {
            case GL_TEXTURE_2D -> 0;
            case GL_TEXTURE_3D -> 1;
            case GL_TEXTURE_2D_ARRAY -> 2;
            case GL_TEXTURE_CUBE_MAP -> 3;
            case GL_TEXTURE_CUBE_MAP_ARRAY -> 4;
            default -> throw new UnsupportedOperationException("Incomplete sampler target: " + target);
        };
        int kind = switch (scalar) {
            case SPVC_BASETYPE_FP32 -> 0;
            case SPVC_BASETYPE_INT32 -> 1;
            case SPVC_BASETYPE_UINT32 -> 2;
            default -> throw new UnsupportedOperationException("Incomplete sampler scalar: " + scalar);
        };
        return FIRST - shape * FORMATS.length - kind;
    }

    public static boolean contains(int name) {
        return name <= FIRST && name > FIRST - TYPES.length * FORMATS.length;
    }

    public long handle(int name) {
        if (!contains(name)) throw new IllegalArgumentException("Unknown sampler placeholder: " + name);
        int index = FIRST - name;
        if (textures[index] == 0) textures[index] = create(index / FORMATS.length, index % FORMATS.length);
        return textures[index];
    }

    private static long create(int shape, int kind) {
        long device = SDLGPUGate.device().getDevice();
        int layers = shape >= 3 ? 6 : 1, bytes = kind == 0 ? 4 : 16;
        try (var stack = MemoryStack.stackPush()) {
            long texture = Sdl.require(
                    SDL_CreateGPUTexture(
                            device,
                            SDL_GPUTextureCreateInfo.calloc(stack)
                                    .type(TYPES[shape])
                                    .format(FORMATS[kind])
                                    .usage(SDL_GPU_TEXTUREUSAGE_SAMPLER)
                                    .width(1)
                                    .height(1)
                                    .layer_count_or_depth(layers)
                                    .num_levels(1)),
                    "Create incomplete sampler texture");
            long transfer = 0, commands = 0;
            try {
                transfer = Sdl.require(
                        SDL_CreateGPUTransferBuffer(
                                device,
                                SDL_GPUTransferBufferCreateInfo.calloc(stack)
                                        .usage(SDL_GPU_TRANSFERBUFFERUSAGE_UPLOAD)
                                        .size(bytes)),
                        "Create incomplete sampler upload");
                var mapped = SDL_MapGPUTransferBuffer(device, transfer, false, bytes);
                if (mapped == null) throw Sdl.failure("Map incomplete sampler upload");
                MemoryUtil.memSet(mapped, 0);
                if (kind == 0) mapped.put(3, (byte) 255);
                else mapped.order(ByteOrder.nativeOrder()).putInt(12, 1);
                SDL_UnmapGPUTransferBuffer(device, transfer);
                commands = Sdl.require(SDL_AcquireGPUCommandBuffer(device), "Acquire incomplete sampler commands");
                long copy = Sdl.require(SDL_BeginGPUCopyPass(commands), "Begin incomplete sampler upload");
                var source = SDL_GPUTextureTransferInfo.calloc(stack).transfer_buffer(transfer);
                var destination = SDL_GPUTextureRegion.calloc(stack)
                        .texture(texture)
                        .w(1)
                        .h(1)
                        .d(1);
                for (int layer = 0; layer < layers; layer++)
                    SDL_UploadToGPUTexture(copy, source, destination.layer(layer), false);
                SDL_EndGPUCopyPass(copy);
                if (!SDL_SubmitGPUCommandBuffer(commands)) throw Sdl.failure("Submit incomplete sampler upload");
                commands = 0;
                return texture;
            } catch (RuntimeException | Error failure) {
                if (commands != 0) SDL_CancelGPUCommandBuffer(commands);
                SDL_ReleaseGPUTexture(device, texture);
                throw failure;
            } finally {
                if (transfer != 0) SDL_ReleaseGPUTransferBuffer(device, transfer);
            }
        }
    }

    @Override
    public void close() {
        for (int i = 0; i < textures.length; i++)
            if (textures[i] != 0) {
                SDL_ReleaseGPUTexture(SDLGPUGate.device().getDevice(), textures[i]);
                textures[i] = 0;
            }
    }
}
