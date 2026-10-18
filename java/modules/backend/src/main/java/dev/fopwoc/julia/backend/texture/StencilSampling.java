package dev.fopwoc.julia.backend.texture;

import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.sdl.SDLGPU.*;

import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import com.gtnewhorizons.angelica.sdlgpu.frame.FrameManager;
import com.gtnewhorizons.angelica.sdlgpu.resource.ResourceManager;
import dev.fopwoc.julia.backend.BackendInternals;
import dev.fopwoc.julia.backend.compat.StencilReference;
import org.lwjgl.sdl.*;
import org.lwjgl.system.MemoryStack;

/** Correctness-first GPU stencil extraction. Integer sampling is derived afresh before each consuming draw. */
final class StencilSampling implements AutoCloseable {
    private static final String VERTEX = """
            #version 460 core
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    private static final String FRAGMENT = """
            #version 460 core
            uniform vec4 stencilValue;
            layout(location = 0) out uint result;
            void main() { result = uint(stencilValue.x); }
            """;
    private long texture;
    private long pipeline;
    private long vertex;
    private long fragment;

    long texture() {
        return texture;
    }

    void refresh(ContextState state, long source, ResourceManager.TextureMeta meta) {
        var frames = BackendInternals.frames();
        var resources = BackendInternals.resources();
        if (frames.getCommandBuffer() == 0)
            throw new IllegalStateException("Stencil sampling requires an active frame");
        if (texture == 0)
            texture = resources.createScratchTexture(
                    SDL_GPU_TEXTUREFORMAT_R8_UINT,
                    meta.width(),
                    meta.height(),
                    SDL_GPU_TEXTUREUSAGE_COLOR_TARGET | SDL_GPU_TEXTUREUSAGE_SAMPLER);
        if (pipeline == 0) createPipeline(meta.sdlFormat());
        BackendInternals.clears().materializePendingClearForTexture(state, source);
        frames.endRenderPassIfActive(FrameManager.PASS_END_READBACK);
        long pass = 0;
        int drawFbo = state.boundFboId, readFbo = state.boundReadFboId;
        boolean seed = state.needsSeed;
        try (var stack = MemoryStack.stackPush()) {
            var color = SDL_GPUColorTargetInfo.calloc(1, stack);
            color.get(0).texture(texture).load_op(SDL_GPU_LOADOP_DONT_CARE).store_op(SDL_GPU_STOREOP_STORE);
            var depth = SDL_GPUDepthStencilTargetInfo.calloc(stack)
                    .texture(source)
                    .load_op(SDL_GPU_LOADOP_LOAD)
                    .store_op(SDL_GPU_STOREOP_STORE)
                    .stencil_load_op(SDL_GPU_LOADOP_LOAD)
                    .stencil_store_op(SDL_GPU_STOREOP_STORE);
            pass = frames.beginRenderPass(color, depth);
            if (pass == 0)
                throw new IllegalStateException("Cannot begin stencil extraction: " + SDLError.SDL_GetError());
            SDL_BindGPUGraphicsPipeline(pass, pipeline);
            SDL_SetGPUViewport(
                    pass,
                    SDL_GPUViewport.calloc(stack)
                            .w(meta.width())
                            .h(meta.height())
                            .max_depth(1f));
            SDL_SetGPUScissor(pass, SDL_Rect.calloc(stack).w(meta.width()).h(meta.height()));
            var value = stack.calloc(16);
            // Every pixel passes exactly one reference test. No blending, CPU download or source mutation.
            for (int reference = 0; reference < 256; reference++) {
                value.putFloat(0, reference);
                SDL_PushGPUFragmentUniformData(frames.getCommandBuffer(), 0, value);
                StencilReference.set(pass, (byte) reference);
                SDL_DrawGPUPrimitives(pass, 3, 1, 0, 0);
            }
            resources.markTextureContentDefined(texture);
            resources.markTextureWritten(texture);
        } finally {
            if (pass != 0) frames.endRenderPassIfActive(FrameManager.PASS_END_READBACK);
            // Native extraction clobbers bindings, not the application's logical framebuffer selection.
            state.invalidateMirror();
            state.boundFboId = drawFbo;
            state.boundReadFboId = readFbo;
            state.needsSeed = seed;
        }
    }

    private void createPipeline(int depthFormat) {
        var shaders = BackendInternals.shaders();
        int vs = shaders.createShader(GL_VERTEX_SHADER), fs = shaders.createShader(GL_FRAGMENT_SHADER);
        int program = shaders.createProgram();
        try {
            shaders.shaderSource(vs, VERTEX);
            shaders.compileShader(vs);
            shaders.shaderSource(fs, FRAGMENT);
            shaders.compileShader(fs);
            shaders.attachShader(program, vs);
            shaders.attachShader(program, fs);
            shaders.linkProgram(program);
            var linked = shaders.getProgram(program);
            if (linked == null || !linked.linked || linked.fragmentUboSize != 16) {
                throw new IllegalStateException(
                        "Stencil extraction shader failed: " + shaders.getProgramInfoLog(program));
            }
            vertex = shaders.createSDLShader(linked.vertexSpirv, SDL_GPU_SHADERSTAGE_VERTEX, 0, 0, 0, 0);
            fragment = shaders.createSDLShader(linked.fragmentSpirv, SDL_GPU_SHADERSTAGE_FRAGMENT, 0, 1, 0, 0);
            if (vertex == 0 || fragment == 0)
                throw new IllegalStateException("Cannot create stencil extraction shaders");
            try (var stack = MemoryStack.stackPush()) {
                var targets = SDL_GPUColorTargetDescription.calloc(1, stack);
                targets.get(0).format(SDL_GPU_TEXTUREFORMAT_R8_UINT);
                var stencil = SDL_GPUDepthStencilState.calloc(stack)
                        .compare_op(SDL_GPU_COMPAREOP_ALWAYS)
                        .enable_stencil_test(true)
                        .compare_mask((byte) 255)
                        .write_mask((byte) 0);
                stencil.front_stencil_state()
                        .compare_op(SDL_GPU_COMPAREOP_EQUAL)
                        .fail_op(SDL_GPU_STENCILOP_KEEP)
                        .pass_op(SDL_GPU_STENCILOP_KEEP)
                        .depth_fail_op(SDL_GPU_STENCILOP_KEEP);
                stencil.back_stencil_state().set(stencil.front_stencil_state());
                var info = SDL_GPUGraphicsPipelineCreateInfo.calloc(stack)
                        .vertex_shader(vertex)
                        .fragment_shader(fragment)
                        .primitive_type(SDL_GPU_PRIMITIVETYPE_TRIANGLELIST)
                        .vertex_input_state(SDL_GPUVertexInputState.calloc(stack))
                        .rasterizer_state(SDL_GPURasterizerState.calloc(stack)
                                .fill_mode(SDL_GPU_FILLMODE_FILL)
                                .cull_mode(SDL_GPU_CULLMODE_NONE)
                                .front_face(SDL_GPU_FRONTFACE_COUNTER_CLOCKWISE))
                        .multisample_state(SDL_GPUMultisampleState.calloc(stack).sample_count(SDL_GPU_SAMPLECOUNT_1))
                        .depth_stencil_state(stencil)
                        .target_info(SDL_GPUGraphicsPipelineTargetInfo.calloc(stack)
                                .num_color_targets(1)
                                .color_target_descriptions(targets)
                                .has_depth_stencil_target(true)
                                .depth_stencil_format(depthFormat));
                pipeline = SDL_CreateGPUGraphicsPipeline(SDLGPUGate.device().getDevice(), info);
                if (pipeline == 0)
                    throw new IllegalStateException(
                            "Cannot create stencil extraction pipeline: " + SDLError.SDL_GetError());
            }
        } finally {
            shaders.releaseProgramFinal(program);
            shaders.deleteShader(vs);
            shaders.deleteShader(fs);
        }
    }

    @Override
    public void close() {
        long device = SDLGPUGate.device().getDevice();
        if (pipeline != 0) SDL_ReleaseGPUGraphicsPipeline(device, pipeline);
        if (vertex != 0) SDL_ReleaseGPUShader(device, vertex);
        if (fragment != 0) SDL_ReleaseGPUShader(device, fragment);
        if (texture != 0) BackendInternals.resources().releaseTextureHandle(texture);
        pipeline = 0;
        vertex = 0;
        fragment = 0;
        texture = 0;
    }
}
