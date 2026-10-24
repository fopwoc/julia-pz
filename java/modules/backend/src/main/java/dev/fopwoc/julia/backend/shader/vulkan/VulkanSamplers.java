package dev.fopwoc.julia.backend.shader.vulkan;

import static org.lwjgl.sdl.SDLGPU.SDL_CreateGPUComputePipeline;
import static org.lwjgl.sdl.SDLGPU.SDL_CreateGPUShader;
import static org.lwjgl.sdl.SDLGPU.SDL_GPU_SHADERFORMAT_SPIRV;
import static org.lwjgl.sdl.SDLGPU.SDL_GPU_SHADERSTAGE_VERTEX;
import static org.lwjgl.sdl.SDLProperties.*;
import static org.lwjgl.util.spvc.Spv.SpvDecorationBinding;
import static org.lwjgl.util.spvc.Spv.SpvDimBuffer;
import static org.lwjgl.util.spvc.Spvc.*;

import dev.fopwoc.julia.backend.shader.InstanceIndexSpirv;
import dev.fopwoc.julia.backend.shader.NonUniformSamplerSpirv;
import dev.fopwoc.julia.backend.shader.SamplerResources;
import dev.fopwoc.julia.backend.utils.Sdl;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.opengl.GL20;
import org.lwjgl.sdl.SDL_GPUComputePipelineCreateInfo;
import org.lwjgl.sdl.SDL_GPUShaderCreateInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.SpvcReflectedResource;

/** Retains active sampled-resource array widths for Vulkan descriptor layout and write construction. */
public final class VulkanSamplers {
    private VulkanSamplers() {}

    public static long create(long device, SDL_GPUShaderCreateInfo info) {
        if (info.format() != SDL_GPU_SHADERFORMAT_SPIRV)
            throw new IllegalArgumentException("Vulkan shaders require SPIR-V");
        var originalCode = info.code();
        var located = ResourceLocationsSpirv.translate(originalCode);
        var indexed = InstanceIndexSpirv.translate(
                located, info.stage() == SDL_GPU_SHADERSTAGE_VERTEX ? GL20.GL_VERTEX_SHADER : GL20.GL_FRAGMENT_SHADER);
        var code = NonUniformSamplerSpirv.translate(indexed);
        int previous = info.props();
        int properties = properties(code, info.num_samplers(), previous);
        try {
            if (indexed != located && !SDL_SetBooleanProperty(properties, "julia.gpu.requires_draw_parameters", true))
                throw Sdl.failure("Set Vulkan draw parameter requirement");
            if (code != indexed && !SDL_SetBooleanProperty(properties, "julia.gpu.requires_nonuniform_samplers", true))
                throw Sdl.failure("Set Vulkan non-uniform sampler requirement");
            info.code(code).props(properties);
            return SDL_CreateGPUShader(device, info);
        } finally {
            info.code(originalCode).props(previous);
            SDL_DestroyProperties(properties);
        }
    }

    public static long createCompute(long device, SDL_GPUComputePipelineCreateInfo info) {
        if (info.format() != SDL_GPU_SHADERFORMAT_SPIRV)
            throw new IllegalArgumentException("Vulkan compute shaders require SPIR-V");
        int previous = info.props();
        var originalCode = info.code();
        var code = ResourceLocationsSpirv.translate(originalCode);
        int properties = properties(code, info.num_samplers(), previous);
        try {
            info.code(code).props(properties);
            return SDL_CreateGPUComputePipeline(device, info);
        } finally {
            info.code(originalCode).props(previous);
            SDL_DestroyProperties(properties);
        }
    }

    private static int properties(ByteBuffer code, int samplers, int previous) {
        int properties = SDL_CreateProperties();
        if (properties == 0) throw Sdl.failure("Create Vulkan shader properties");
        try (var stack = MemoryStack.stackPush()) {
            if (previous != 0 && !SDL_CopyProperties(previous, properties))
                throw Sdl.failure("Copy Vulkan shader properties");
            var pointer = stack.pointers(0);
            SamplerResources.check(spvc_context_create(pointer));
            long context = pointer.get(0);
            try {
                var words = code.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
                SamplerResources.check(spvc_context_parse_spirv(context, words, words.remaining(), pointer));
                SamplerResources.check(spvc_context_create_compiler(
                        context, SPVC_BACKEND_NONE, pointer.get(0), SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, pointer));
                long compiler = pointer.get(0);
                long resources = SamplerResources.activeResources(compiler, stack);
                var count = stack.pointers(0);
                SamplerResources.check(spvc_resources_get_resource_list_for_type(
                        resources, SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, pointer, count));
                var list = SpvcReflectedResource.create(pointer.get(0), Math.toIntExact(count.get(0)));
                for (var resource : list) {
                    int width = SamplerResources.width(compiler, resource);
                    long type = spvc_compiler_get_type_handle(compiler, resource.type_id());
                    int kind = 0;
                    if (spvc_type_get_image_dimension(type) == SpvDimBuffer) {
                        int scalar = spvc_type_get_basetype(
                                spvc_compiler_get_type_handle(compiler, spvc_type_get_image_sampled_type(type)));
                        kind = switch (scalar) {
                            case SPVC_BASETYPE_FP32 -> 1;
                            case SPVC_BASETYPE_INT32 -> 2;
                            case SPVC_BASETYPE_UINT32 -> 3;
                            default ->
                                throw new UnsupportedOperationException(
                                        "Unsupported Vulkan buffer sampler scalar: " + scalar);
                        };
                    }
                    int binding = spvc_compiler_get_decoration(compiler, resource.id(), SpvDecorationBinding);
                    if (binding < 0 || binding > samplers - width)
                        throw new IllegalStateException("Vulkan sampler binding exceeds the reflected slot count");
                    for (int element = 0; element < width; element++) {
                        if (!SDL_SetNumberProperty(properties, "julia.gpu.sampler_kind." + (binding + element), kind))
                            throw Sdl.failure("Set Vulkan sampler descriptor kind");
                        if (!SDL_SetNumberProperty(
                                properties, "julia.gpu.sampler_width." + (binding + element), element == 0 ? width : 0))
                            throw Sdl.failure("Set Vulkan sampler array width");
                    }
                }
            } finally {
                spvc_context_destroy(context);
            }
            return properties;
        } catch (RuntimeException | Error failure) {
            SDL_DestroyProperties(properties);
            throw failure;
        }
    }
}
