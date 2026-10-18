package dev.fopwoc.julia.backend.shader;

import static org.lwjgl.util.spvc.Spvc.*;

import java.nio.IntBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.Spv;

/** Emits a single stage from a genuinely linked OpenGL program for Angelica's existing Vulkan transform. */
final class LinkedShaderSource {
    private LinkedShaderSource() {}

    static String generate(IntBuffer words) {
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
                                context, SPVC_BACKEND_GLSL, ir, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, pointer));
                long compiler = pointer.get(0);
                check(context, spvc_compiler_create_shader_resources(compiler, pointer));
                long resources = pointer.get(0);
                var resourceList = stack.callocPointer(1);
                var resourceCount = stack.callocPointer(1);
                check(
                        context,
                        spvc_resources_get_resource_list_for_type(
                                resources, SPVC_RESOURCE_TYPE_STORAGE_BUFFER, resourceList, resourceCount));
                if (resourceCount.get(0) != 0)
                    throw new IllegalStateException(
                            "Linked-stage regeneration with shader storage blocks is unsupported: resource names must be preserved");
                // Function IDs distinguish user overrides from actual builtin operations. Renaming
                // these avoids shaderc's Vulkan precision collision without touching variables or
                // accidentally redirecting builtin overloads such as vector min/max/clamp.
                for (int i = 5; i < words.limit(); ) {
                    int word = words.get(i), count = word >>> 16;
                    if (count == 0 || count > words.limit() - i)
                        throw new IllegalStateException("Malformed linked SPIR-V instruction");
                    if ((word & 65535) == Spv.SpvOpFunction) {
                        int id = words.get(i + 2);
                        if (!"main".equals(spvc_compiler_get_name(compiler, id)))
                            spvc_compiler_set_name(compiler, id, "julia_fn_" + id);
                    }
                    i += count;
                }
                check(context, spvc_compiler_create_compiler_options(compiler, pointer));
                long options = pointer.get(0);
                check(context, spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_GLSL_VERSION, 460));
                check(context, spvc_compiler_install_compiler_options(compiler, options));
                check(context, spvc_compiler_compile(compiler, pointer));
                return pointer.getStringUTF8(0);
            } finally {
                spvc_context_destroy(context);
            }
        }
    }

    private static void check(long context, int result) {
        if (result != SPVC_SUCCESS)
            throw new IllegalStateException(
                    "Linked GLSL emission: " + (context == 0 ? result : spvc_context_get_last_error_string(context)));
    }
}
