package dev.fopwoc.julia.backend.shader;

import static org.lwjgl.util.spvc.Spvc.*;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spv;

/** Reads compiler-folded OpenGL uniform initializers before Vulkan UBO lowering discards them. */
final class UniformInitializers {
    private UniformInitializers() {}

    static Map<String, float[]> read(IntBuffer words) {
        var result = new LinkedHashMap<String, float[]>();
        try (var stack = MemoryStack.stackPush()) {
            var pointer = stack.callocPointer(1);
            check(0, spvc_context_create(pointer));
            long context = pointer.get(0);
            try {
                check(context, spvc_context_parse_spirv(context, words, words.remaining(), pointer));
                long ir = pointer.get(0);
                check(
                        context,
                        spvc_context_create_compiler(
                                context, SPVC_BACKEND_NONE, ir, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, pointer));
                long compiler = pointer.get(0);
                for (int i = 5; i < words.limit(); ) {
                    int instruction = words.get(i), count = instruction >>> 16;
                    if (count == 0 || count > words.limit() - i)
                        throw new IllegalStateException("Malformed uniform initializer SPIR-V");
                    if ((instruction & 65535) == Spv.SpvOpVariable
                            && count == 5
                            && words.get(i + 3) == Spv.SpvStorageClassUniformConstant) {
                        String name = spvc_compiler_get_name(compiler, words.get(i + 2));
                        var values = new ArrayList<Float>();
                        append(compiler, words.get(i + 4), values);
                        var data = new float[values.size()];
                        for (int n = 0; n < data.length; n++) data[n] = values.get(n);
                        result.put(name, data);
                    }
                    i += count;
                }
            } finally {
                spvc_context_destroy(context);
            }
        }
        return Map.copyOf(result);
    }

    private static void append(long compiler, int id, ArrayList<Float> values) {
        long constant = spvc_compiler_get_constant_handle(compiler, id);
        long type = spvc_compiler_get_type_handle(compiler, spvc_constant_get_type(constant));
        if (spvc_type_get_num_array_dimensions(type) > 0) {
            try (var stack = MemoryStack.stackPush()) {
                var pointer = stack.callocPointer(1);
                var count = stack.callocPointer(1);
                spvc_constant_get_subconstants(constant, pointer, count);
                var children = MemoryUtil.memIntBuffer(pointer.get(0), Math.toIntExact(count.get(0)));
                for (int i = 0; i < children.remaining(); i++) append(compiler, children.get(i), values);
            }
            return;
        }
        int base = spvc_type_get_basetype(type);
        if (base != SPVC_BASETYPE_FP32
                && base != SPVC_BASETYPE_INT32
                && base != SPVC_BASETYPE_UINT32
                && base != SPVC_BASETYPE_BOOLEAN)
            throw new IllegalStateException("Unsupported uniform initializer base type: " + base);
        for (int column = 0; column < spvc_type_get_columns(type); column++)
            for (int row = 0; row < spvc_type_get_vector_size(type); row++)
                values.add(
                        base == SPVC_BASETYPE_FP32
                                ? spvc_constant_get_scalar_fp32(constant, column, row)
                                : Float.intBitsToFloat(spvc_constant_get_scalar_u32(constant, column, row)));
    }

    private static void check(long context, int code) {
        if (code != SPVC_SUCCESS)
            throw new IllegalStateException("Uniform initializer reflection: "
                    + (context == 0 ? code : spvc_context_get_last_error_string(context)));
    }
}
