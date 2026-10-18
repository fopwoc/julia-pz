package dev.fopwoc.julia.probe.device;

import static org.lwjgl.sdl.SDLGPU.*;

import com.gtnewhorizons.angelica.sdlgpu.device.Device;
import dev.fopwoc.julia.backend.utils.Sdl;
import java.io.IOException;
import org.lwjgl.sdl.SDL_GPUColorTargetDescription;
import org.lwjgl.sdl.SDL_GPUColorTargetInfo;
import org.lwjgl.sdl.SDL_GPUGraphicsPipelineCreateInfo;
import org.lwjgl.sdl.SDL_GPUGraphicsPipelineTargetInfo;
import org.lwjgl.sdl.SDL_GPUMultisampleState;
import org.lwjgl.sdl.SDL_GPURasterizerState;
import org.lwjgl.sdl.SDL_GPUSamplerCreateInfo;
import org.lwjgl.sdl.SDL_GPUTextureCreateInfo;
import org.lwjgl.sdl.SDL_GPUTextureRegion;
import org.lwjgl.sdl.SDL_GPUTextureSamplerBinding;
import org.lwjgl.sdl.SDL_GPUTextureTransferInfo;
import org.lwjgl.sdl.SDL_GPUTransferBufferCreateInfo;
import org.lwjgl.sdl.SDL_GPUVertexInputState;
import org.lwjgl.system.MemoryStack;

/** Uploads an asymmetric texture and draws it with Angelica-translated OpenGL shaders. */
final class TexturedDraw {
    private static final int EDGE = TexturePattern.EDGE;

    private TexturedDraw() {}

    static void render(Device owner, long commands, long target) throws IOException {
        long device = owner.getDevice();
        long texture = 0;
        long upload = 0;
        long sampler = 0;
        long pipeline = 0;
        try (var shaders = new TranslatedProgram(owner);
                var stack = MemoryStack.stackPush()) {
            texture = Sdl.require(
                    SDL_CreateGPUTexture(
                            device,
                            SDL_GPUTextureCreateInfo.calloc(stack)
                                    .type(SDL_GPU_TEXTURETYPE_2D)
                                    .format(SDL_GPU_TEXTUREFORMAT_R8G8B8A8_UNORM)
                                    .usage(SDL_GPU_TEXTUREUSAGE_SAMPLER)
                                    .width(EDGE)
                                    .height(EDGE)
                                    .layer_count_or_depth(1)
                                    .num_levels(1)
                                    .sample_count(SDL_GPU_SAMPLECOUNT_1)),
                    "Create sampled texture");
            upload = Sdl.require(
                    SDL_CreateGPUTransferBuffer(
                            device,
                            SDL_GPUTransferBufferCreateInfo.calloc(stack)
                                    .usage(SDL_GPU_TRANSFERBUFFERUSAGE_UPLOAD)
                                    .size(EDGE * EDGE * 4)),
                    "Create upload buffer");
            var mapped = SDL_MapGPUTransferBuffer(device, upload, false, EDGE * EDGE * 4);
            if (mapped == null) throw Sdl.failure("Map upload buffer");
            try {
                for (int y = 0; y < EDGE; y++) {
                    for (int x = 0; x < EDGE; x++) {
                        int color = TexturePattern.sourceArgb(x, y);
                        mapped.put((byte) (color >>> 16))
                                .put((byte) (color >>> 8))
                                .put((byte) color)
                                .put((byte) 255);
                    }
                }
            } finally {
                SDL_UnmapGPUTransferBuffer(device, upload);
            }
            sampler = Sdl.require(
                    SDL_CreateGPUSampler(
                            device,
                            SDL_GPUSamplerCreateInfo.calloc(stack)
                                    .min_filter(SDL_GPU_FILTER_NEAREST)
                                    .mag_filter(SDL_GPU_FILTER_NEAREST)
                                    .mipmap_mode(SDL_GPU_SAMPLERMIPMAPMODE_NEAREST)
                                    .address_mode_u(SDL_GPU_SAMPLERADDRESSMODE_CLAMP_TO_EDGE)
                                    .address_mode_v(SDL_GPU_SAMPLERADDRESSMODE_CLAMP_TO_EDGE)
                                    .address_mode_w(SDL_GPU_SAMPLERADDRESSMODE_CLAMP_TO_EDGE)),
                    "Create sampler");
            var colors = SDL_GPUColorTargetDescription.calloc(1, stack);
            colors.get(0).format(SDL_GPU_TEXTUREFORMAT_R8G8B8A8_UNORM);
            pipeline = Sdl.require(
                    SDL_CreateGPUGraphicsPipeline(
                            device,
                            SDL_GPUGraphicsPipelineCreateInfo.calloc(stack)
                                    .vertex_shader(shaders.vertex())
                                    .fragment_shader(shaders.fragment())
                                    .primitive_type(SDL_GPU_PRIMITIVETYPE_TRIANGLELIST)
                                    .rasterizer_state(SDL_GPURasterizerState.calloc(stack)
                                            .cull_mode(SDL_GPU_CULLMODE_NONE)
                                            .fill_mode(SDL_GPU_FILLMODE_FILL))
                                    .multisample_state(SDL_GPUMultisampleState.calloc(stack)
                                            .sample_count(SDL_GPU_SAMPLECOUNT_1))
                                    .target_info(SDL_GPUGraphicsPipelineTargetInfo.calloc(stack)
                                            .num_color_targets(1)
                                            .color_target_descriptions(colors))
                                    .vertex_input_state(SDL_GPUVertexInputState.calloc(stack))),
                    "Create textured pipeline");
            long copy = Sdl.require(SDL_BeginGPUCopyPass(commands), "Begin texture upload");
            SDL_UploadToGPUTexture(
                    copy,
                    SDL_GPUTextureTransferInfo.calloc(stack)
                            .transfer_buffer(upload)
                            .pixels_per_row(EDGE)
                            .rows_per_layer(EDGE),
                    SDL_GPUTextureRegion.calloc(stack)
                            .texture(texture)
                            .w(EDGE)
                            .h(EDGE)
                            .d(1),
                    false);
            SDL_EndGPUCopyPass(copy);
            var targets = SDL_GPUColorTargetInfo.calloc(1, stack);
            targets.get(0).texture(target).load_op(SDL_GPU_LOADOP_CLEAR).store_op(SDL_GPU_STOREOP_STORE);
            long render = Sdl.require(SDL_BeginGPURenderPass(commands, targets, null), "Begin textured render");
            SDL_BindGPUGraphicsPipeline(render, pipeline);
            var bindings = SDL_GPUTextureSamplerBinding.calloc(1, stack);
            bindings.get(0).texture(texture).sampler(sampler);
            SDL_BindGPUFragmentSamplers(render, 0, bindings);
            SDL_DrawGPUPrimitives(render, 3, 1, 0, 0);
            SDL_EndGPURenderPass(render);
        } finally {
            // SDL retains resources referenced by pending command buffers until the GPU is finished.
            if (pipeline != 0) SDL_ReleaseGPUGraphicsPipeline(device, pipeline);
            if (sampler != 0) SDL_ReleaseGPUSampler(device, sampler);
            if (upload != 0) SDL_ReleaseGPUTransferBuffer(device, upload);
            if (texture != 0) SDL_ReleaseGPUTexture(device, texture);
        }
    }
}
