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
        SDL_GPU_TEXTUREFORMAT_R32G32B32A32_UINT,
        SDL_GPU_TEXTUREFORMAT_D32_FLOAT
    };
    private final long[] textures = new long[TYPES.length * FORMATS.length];

    public static int name(int target, int scalar, boolean shadow) {
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
        if (shadow) {
            if (kind != 0 || target == GL_TEXTURE_3D) throw new IllegalArgumentException("Invalid shadow sampler type");
            kind = 3;
        }
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
        int layers = shape >= 3 ? 6 : 1, bytes = kind == 0 || kind == 3 ? 4 : 16;
        try (var stack = MemoryStack.stackPush()) {
            long texture = Sdl.require(
                    SDL_CreateGPUTexture(
                            device,
                            SDL_GPUTextureCreateInfo.calloc(stack)
                                    .type(TYPES[shape])
                                    .format(FORMATS[kind])
                                    .usage(SDL_GPU_TEXTUREUSAGE_SAMPLER
                                            | (kind == 3 ? SDL_GPU_TEXTUREUSAGE_DEPTH_STENCIL_TARGET : 0))
                                    .width(1)
                                    .height(1)
                                    .layer_count_or_depth(layers)
                                    .num_levels(1)),
                    "Create incomplete sampler texture");
            if (kind == 3) {
                try {
                    clearDepth(stack, device, texture, layers);
                    return texture;
                } catch (RuntimeException | Error failure) {
                    SDL_ReleaseGPUTexture(device, texture);
                    throw failure;
                }
            }
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

    private static void clearDepth(MemoryStack stack, long device, long texture, int layers) {
        long commands = Sdl.require(SDL_AcquireGPUCommandBuffer(device), "Acquire shadow placeholder commands");
        try {
            var depth = SDL_GPUDepthStencilTargetInfo.calloc(stack)
                    .texture(texture)
                    .clear_depth(0)
                    .load_op(SDL_GPU_LOADOP_CLEAR)
                    .store_op(SDL_GPU_STOREOP_STORE)
                    .stencil_load_op(SDL_GPU_LOADOP_DONT_CARE)
                    .stencil_store_op(SDL_GPU_STOREOP_DONT_CARE);
            for (int layer = 0; layer < layers; layer++) {
                depth.layer((byte) layer);
                long pass = Sdl.require(SDL_BeginGPURenderPass(commands, null, depth), "Clear shadow placeholder");
                SDL_EndGPURenderPass(pass);
            }
            if (!SDL_SubmitGPUCommandBuffer(commands)) throw Sdl.failure("Submit shadow placeholder clear");
        } catch (RuntimeException | Error failure) {
            SDL_CancelGPUCommandBuffer(commands);
            throw failure;
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
