package dev.fopwoc.julia.backend.shader;

import static org.lwjgl.util.spvc.Spvc.*;

import com.gtnewhorizons.angelica.sdlgpu.shader.SamplerOverflow;
import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager.ProgramObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.SpvcBufferRange;
import org.lwjgl.util.spvc.SpvcReflectedResource;

/** Reflects entry-point usage rather than counting sampler declarations or texture-unit assignments. */
final class UniformReflection {
    private UniformReflection() {}

    static UniformResources read(ProgramObject program) {
        var variables = new ArrayList<UniformVariable>();
        stage(program, program.vertexSpirv, StorageBlock.VERTEX, variables);
        stage(program, program.fragmentSpirv, StorageBlock.FRAGMENT, variables);
        stage(program, program.computeSpirv, StorageBlock.COMPUTE, variables);
        return new UniformResources(variables);
    }

    private static void stage(ProgramObject program, ByteBuffer spirv, int stage, List<UniformVariable> output) {
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
                check(spvc_compiler_get_active_interface_variables(compiler, pointer));
                long active = pointer.get(0);
                check(spvc_compiler_create_shader_resources_for_active_variables(compiler, pointer, active));
                long resources = pointer.get(0);
                var count = stack.pointers(0);
                for (int unsupported : new int[] {
                    SPVC_RESOURCE_TYPE_ATOMIC_COUNTER,
                    SPVC_RESOURCE_TYPE_PUSH_CONSTANT,
                    SPVC_RESOURCE_TYPE_SEPARATE_SAMPLERS
                }) {
                    check(spvc_resources_get_resource_list_for_type(resources, unsupported, pointer, count));
                    if (count.get(0) != 0)
                        throw new UnsupportedOperationException(
                                "Uniform interface reflection of atomic counters, push constants or separate samplers");
                }
                for (int resourceType : new int[] {
                    SPVC_RESOURCE_TYPE_SAMPLED_IMAGE,
                    SPVC_RESOURCE_TYPE_SEPARATE_IMAGE,
                    SPVC_RESOURCE_TYPE_STORAGE_IMAGE
                }) {
                    check(spvc_resources_get_resource_list_for_type(resources, resourceType, pointer, count));
                    for (var resource : SpvcReflectedResource.create(pointer.get(0), Math.toIntExact(count.get(0)))) {
                        String name = resource.nameString();
                        boolean sampler =
                                resourceType == SPVC_RESOURCE_TYPE_SAMPLED_IMAGE || SamplerOverflow.isMoved(name);
                        name = SamplerOverflow.originalName(name);
                        long type = spvc_compiler_get_type_handle(compiler, resource.type_id());
                        int size = arraySize(type);
                        output.add(variable(
                                program,
                                name,
                                OpaqueUniformType.read(compiler, type, sampler),
                                size,
                                stage,
                                spvc_type_get_num_array_dimensions(type) != 0));
                    }
                }
                check(spvc_resources_get_resource_list_for_type(
                        resources, SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, pointer, count));
                for (var block : SpvcReflectedResource.create(pointer.get(0), Math.toIntExact(count.get(0)))) {
                    String blockName = spvc_compiler_get_name(compiler, block.base_type_id());
                    if (!"gl_DefaultUniformBlock".equals(blockName)
                            && !"_RESERVED_IDENTIFIER_FIXUP_gl_DefaultUniformBlock".equals(blockName))
                        throw new UnsupportedOperationException(
                                "Uniform interface reflection of named uniform blocks: " + blockName);
                    long base = spvc_compiler_get_type_handle(compiler, block.base_type_id());
                    check(spvc_compiler_get_active_buffer_ranges(compiler, block.id(), pointer, count));
                    for (var range : SpvcBufferRange.create(pointer.get(0), Math.toIntExact(count.get(0)))) {
                        int member = range.index();
                        String name = spvc_compiler_get_member_name(compiler, block.base_type_id(), member);
                        long type = spvc_compiler_get_type_handle(compiler, spvc_type_get_member_type(base, member));
                        if (spvc_type_get_basetype(type) == SPVC_BASETYPE_STRUCT)
                            throw new UnsupportedOperationException(
                                    "Uniform interface reflection of struct uniform: " + name);
                        int glType = ResourceType.glType(
                                spvc_type_get_basetype(type),
                                spvc_type_get_vector_size(type),
                                spvc_type_get_columns(type));
                        if (program.boolUniforms.contains(name))
                            glType = ResourceType.glType(SPVC_BASETYPE_BOOLEAN, spvc_type_get_vector_size(type), 1);
                        output.add(variable(
                                program,
                                name,
                                glType,
                                arraySize(type),
                                stage,
                                spvc_type_get_num_array_dimensions(type) != 0));
                    }
                }
            } finally {
                spvc_context_destroy(context);
            }
        }
    }

    private static UniformVariable variable(
            ProgramObject program, String name, int type, int size, int stage, boolean array) {
        int location = program.nameToLocation.getInt(name);
        String resourceName = array && !name.endsWith("[0]") ? name + "[0]" : name;
        return new UniformVariable(resourceName, type, size, location, stage);
    }

    private static int arraySize(long type) {
        int dimensions = spvc_type_get_num_array_dimensions(type);
        if (dimensions > 1 || dimensions == 1 && !spvc_type_array_dimension_is_literal(type, 0))
            throw new UnsupportedOperationException(
                    "Uniform interface reflection of multidimensional or runtime arrays");
        return dimensions == 0 ? 1 : spvc_type_get_array_dimension(type, 0);
    }

    private static void check(int result) {
        if (result != SPVC_SUCCESS) throw new IllegalStateException("Uniform interface reflection failed: " + result);
    }
}
