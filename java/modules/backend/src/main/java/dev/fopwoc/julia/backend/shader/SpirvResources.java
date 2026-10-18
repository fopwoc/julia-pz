package dev.fopwoc.julia.backend.shader;

import static org.lwjgl.util.spvc.Spv.*;
import static org.lwjgl.util.spvc.Spvc.*;

import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntBinaryOperator;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.SpvcReflectedResource;

/** Reflects compiled storage layouts, including the struct arrays used by PZ's instanced rendering. */
final class SpirvResources {
    private SpirvResources() {}

    static List<StorageBlock> read(ShaderManager.ProgramObject program) {
        var blocks = new ArrayList<StorageBlock>();
        if (program.computeSpirv != null) {
            var map = program.computeBindingMap;
            blocks.addAll(stage(program.computeSpirv, StorageBlock.COMPUTE, (set, binding) -> {
                if (set != 0 && set != 1)
                    throw new IllegalStateException("Unexpected compute storage descriptor set: " + set);
                int index = set == 0
                        ? binding - map.samplerGlSlots().length - map.roStorageTextureGlSlots().length
                        : binding - map.rwStorageTextureGlSlots().length;
                return slot(set == 0 ? map.roSsboGlSlots() : map.rwSsboGlSlots(), index);
            }));
        } else {
            blocks.addAll(graphics(
                    program.vertexSpirv,
                    StorageBlock.VERTEX,
                    program.vertexResources,
                    program.vertexGraphicsBindingMap));
            blocks.addAll(graphics(
                    program.fragmentSpirv,
                    StorageBlock.FRAGMENT,
                    program.fragmentResources,
                    program.fragmentGraphicsBindingMap));
        }
        return blocks;
    }

    private static List<StorageBlock> graphics(
            ByteBuffer spirv, int stage, ShaderManager.ResourceCounts counts, ShaderManager.GraphicsBindingMap map) {
        return stage(spirv, stage, (set, binding) -> {
            int expectedSet = stage == StorageBlock.VERTEX ? 0 : 2;
            if (set != expectedSet)
                throw new IllegalStateException("Unexpected graphics storage descriptor set: " + set);
            return slot(map.roSsboGlSlots(), binding - counts.numSamplers() - counts.numStorageTextures());
        });
    }

    private static int slot(int[] slots, int index) {
        if (index < 0 || index >= slots.length)
            throw new IllegalStateException("Storage binding does not match the published backend map");
        return slots[index];
    }

    private static List<StorageBlock> stage(ByteBuffer spirv, int stage, IntBinaryOperator binding) {
        if (spirv == null) return List.of();
        try (var stack = MemoryStack.stackPush()) {
            var context = stack.pointers(0);
            check(spvc_context_create(context));
            long ctx = context.get(0);
            try {
                var ir = stack.pointers(0);
                var words = spirv.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
                check(spvc_context_parse_spirv(ctx, words, words.remaining(), ir));
                var compilerPointer = stack.pointers(0);
                check(spvc_context_create_compiler(
                        ctx, SPVC_BACKEND_NONE, ir.get(0), SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, compilerPointer));
                long compiler = compilerPointer.get(0);
                var active = stack.pointers(0);
                check(spvc_compiler_get_active_interface_variables(compiler, active));
                var resources = stack.pointers(0);
                check(spvc_compiler_create_shader_resources_for_active_variables(compiler, resources, active.get(0)));
                var list = stack.pointers(0);
                var count = stack.pointers(0);
                check(spvc_resources_get_resource_list_for_type(
                        resources.get(0), SPVC_RESOURCE_TYPE_STORAGE_BUFFER, list, count));
                var result = new ArrayList<StorageBlock>();
                var reflected = SpvcReflectedResource.create(list.get(0), Math.toIntExact(count.get(0)));
                for (var resource : reflected) {
                    long type = spvc_compiler_get_type_handle(compiler, resource.base_type_id());
                    long declaredType = spvc_compiler_get_type_handle(compiler, resource.type_id());
                    if (spvc_type_get_num_array_dimensions(declaredType) != 0)
                        throw new UnsupportedOperationException("Storage-block instance arrays are unsupported");
                    String name = spvc_compiler_get_name(compiler, resource.base_type_id());
                    if (name == null || name.isEmpty())
                        throw new UnsupportedOperationException("Storage block has no preserved name");
                    String instance = spvc_compiler_get_name(compiler, resource.id());
                    String prefix = instance == null || instance.isEmpty() ? "" : name + ".";
                    var variables = new ArrayList<ResourceVariable>();
                    members(compiler, type, prefix, 0, 0, 1, 0, variables, stack);
                    var size = stack.pointers(0);
                    check(spvc_compiler_get_declared_struct_size_runtime_array(compiler, type, 1, size));
                    int glBinding = binding.applyAsInt(
                            spvc_compiler_get_decoration(compiler, resource.id(), SpvDecorationDescriptorSet),
                            spvc_compiler_get_decoration(compiler, resource.id(), SpvDecorationBinding));
                    result.add(new StorageBlock(name, glBinding, Math.toIntExact(size.get(0)), variables, stage));
                }
                return result;
            } finally {
                spvc_context_destroy(ctx);
            }
        }
    }

    private static void members(
            long compiler,
            long type,
            String prefix,
            int baseOffset,
            int depth,
            int topSize,
            int topStride,
            List<ResourceVariable> out,
            MemoryStack stack) {
        if (depth > 32 || out.size() > 4096)
            throw new UnsupportedOperationException("Storage structure exceeds reflection limits");
        int baseId = spvc_type_get_base_type_id(type);
        var value = stack.ints(0);
        for (int member = 0; member < spvc_type_get_num_member_types(type); member++) {
            String memberName = spvc_compiler_get_member_name(compiler, baseId, member);
            if (memberName == null || memberName.isEmpty())
                throw new UnsupportedOperationException("Storage member has no preserved name");
            check(spvc_compiler_type_struct_member_offset(compiler, type, member, value));
            int offset = Math.addExact(baseOffset, value.get(0));
            long child = spvc_compiler_get_type_handle(compiler, spvc_type_get_member_type(type, member));
            int dimensions = spvc_type_get_num_array_dimensions(child);
            if (dimensions > 1 || dimensions == 1 && !spvc_type_array_dimension_is_literal(child, 0)) {
                throw new UnsupportedOperationException("Multidimensional or specialization-sized storage array");
            }
            int size = dimensions == 0 ? 1 : spvc_type_get_array_dimension(child, 0);
            int stride = 0;
            if (dimensions != 0) {
                check(spvc_compiler_type_struct_member_array_stride(compiler, type, member, value));
                stride = value.get(0);
            }
            int outerSize = depth == 0 ? size : topSize;
            int outerStride = depth == 0 ? stride : topStride;
            String name = prefix + memberName + (dimensions == 0 ? "" : "[0]");
            if (spvc_type_get_basetype(child) == SPVC_BASETYPE_STRUCT) {
                if (depth != 0 && dimensions != 0)
                    throw new UnsupportedOperationException("Nested arrays of storage structs");
                members(
                        compiler,
                        spvc_compiler_get_type_handle(compiler, spvc_type_get_base_type_id(child)),
                        name + ".",
                        offset,
                        depth + 1,
                        outerSize,
                        outerStride,
                        out,
                        stack);
            } else {
                int columns = spvc_type_get_columns(child);
                int matrixStride = 0;
                if (columns > 1) {
                    check(spvc_compiler_type_struct_member_matrix_stride(compiler, type, member, value));
                    matrixStride = value.get(0);
                }
                out.add(new ResourceVariable(
                        name,
                        ResourceType.glType(spvc_type_get_basetype(child), spvc_type_get_vector_size(child), columns),
                        size,
                        offset,
                        stride,
                        matrixStride,
                        spvc_compiler_has_member_decoration(compiler, baseId, member, SpvDecorationRowMajor),
                        outerSize,
                        outerStride));
            }
        }
    }

    private static void check(int result) {
        if (result != SPVC_SUCCESS)
            throw new IllegalStateException("SPIRV-Cross resource reflection failed: " + result);
    }
}
