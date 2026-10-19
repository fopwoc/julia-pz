package dev.fopwoc.julia.backend.texture.buffer;

import static org.lwjgl.opengl.GL31.*;
import static org.lwjgl.util.spvc.Spv.SpvDimBuffer;
import static org.lwjgl.util.spvc.Spvc.*;

import com.gtnewhorizons.angelica.sdlgpu.sampler.SamplerLookup;
import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager.ProgramObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.SpvcReflectedResource;

final class BufferSamplerLayout {
    final ByteBuffer vertex, fragment, compute;
    private final Map<String, Integer> names = new HashMap<>();
    private int generation = Integer.MIN_VALUE;
    private int bufferMask;

    BufferSamplerLayout(ProgramObject program) {
        vertex = program.vertexSpirv;
        fragment = program.fragmentSpirv;
        compute = program.computeSpirv;
        read(vertex);
        read(fragment);
        read(compute);
    }

    boolean matches(ProgramObject program) {
        return vertex == program.vertexSpirv && fragment == program.fragmentSpirv && compute == program.computeSpirv;
    }

    int uniformType(String name, int fallback) {
        return names.getOrDefault(name, fallback);
    }

    int bufferUnits(ProgramObject program) {
        SamplerLookup.resolvedUnits(program, false);
        if (generation == program.samplerUnitsGeneration) return bufferMask;
        int buffers = 0, ordinary = 0;
        for (var name : program.allSamplerNames) {
            int assigned = program.samplerTextureUnits.getInt(name);
            int unit = assigned < 0 ? 0 : assigned;
            if (unit >= 32) throw new IllegalArgumentException("Sampler texture unit out of range: " + unit);
            if (names.containsKey(name)) buffers |= 1 << unit;
            else ordinary |= 1 << unit;
        }
        if ((buffers & ordinary) != 0) throw new IllegalStateException("Different sampler types share a texture unit");
        bufferMask = buffers;
        generation = program.samplerUnitsGeneration;
        return buffers;
    }

    private void read(ByteBuffer spirv) {
        if (spirv == null) return;
        try (var stack = MemoryStack.stackPush()) {
            var pointer = stack.pointers(0);
            check(spvc_context_create(pointer));
            long context = pointer.get(0);
            try {
                var words = spirv.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
                check(spvc_context_parse_spirv(context, words, words.remaining(), pointer));
                check(spvc_context_create_compiler(
                        context, SPVC_BACKEND_NONE, pointer.get(0), SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, pointer));
                long compiler = pointer.get(0);
                check(spvc_compiler_create_shader_resources(compiler, pointer));
                long resources = pointer.get(0);
                var count = stack.pointers(0);
                check(spvc_resources_get_resource_list_for_type(
                        resources, SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, pointer, count));
                var list = SpvcReflectedResource.create(pointer.get(0), Math.toIntExact(count.get(0)));
                for (var resource : list) {
                    long type = spvc_compiler_get_type_handle(compiler, resource.type_id());
                    if (spvc_type_get_image_dimension(type) != SpvDimBuffer) continue;
                    if (spvc_type_get_num_array_dimensions(type) != 0)
                        throw new UnsupportedOperationException(
                                "Arrays of buffer-texture samplers are not implemented");
                    int scalar = spvc_type_get_basetype(
                            spvc_compiler_get_type_handle(compiler, spvc_type_get_image_sampled_type(type)));
                    int glType = switch (scalar) {
                        case SPVC_BASETYPE_FP32 -> GL_SAMPLER_BUFFER;
                        case SPVC_BASETYPE_INT32 -> GL_INT_SAMPLER_BUFFER;
                        case SPVC_BASETYPE_UINT32 -> GL_UNSIGNED_INT_SAMPLER_BUFFER;
                        default ->
                            throw new UnsupportedOperationException(
                                    "Unsupported buffer sampler scalar type: " + scalar);
                    };
                    names.put(resource.nameString(), glType);
                }
            } finally {
                spvc_context_destroy(context);
            }
        }
    }

    private static void check(int result) {
        if (result != SPVC_SUCCESS) throw new IllegalStateException("Buffer sampler reflection failed: " + result);
    }
}
