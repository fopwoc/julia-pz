package dev.fopwoc.julia.backend.shader.metal;

import static org.lwjgl.util.spvc.Spv.*;
import static org.lwjgl.util.spvc.Spvc.*;

import com.gtnewhorizons.angelica.sdlgpu.shader.cross.CrossCompileCache.Output;
import dev.fopwoc.julia.backend.sdl.SdlExtension;
import dev.fopwoc.julia.backend.shader.SamplerResources;
import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.SpvcMslResourceBinding2;
import org.lwjgl.util.spvc.SpvcReflectedResource;

/** A dedicated sampled-image descriptor set becomes an indirect Metal argument buffer at buffer slot 13. */
public final class MetalSamplerShaders {
    private MetalSamplerShaders() {}

    public static Output compile(ByteBuffer spirv, int stage) {
        SdlExtension.requireAbi("JULIA_SDL_SamplerArgumentsABI", 2);
        try (var stack = MemoryStack.stackPush()) {
            var pointer = stack.pointers(0);
            SamplerResources.check(spvc_context_create(pointer));
            long context = pointer.get(0);
            try {
                var words = spirv.duplicate()
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .asIntBuffer();
                SamplerResources.check(spvc_context_parse_spirv(context, words, words.remaining(), pointer));
                SamplerResources.check(spvc_context_create_compiler(
                        context, SPVC_BACKEND_MSL, pointer.get(0), SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, pointer));
                long compiler = pointer.get(0);
                SamplerResources.check(spvc_compiler_get_active_interface_variables(compiler, pointer));
                SamplerResources.check(spvc_compiler_set_enabled_interface_variables(compiler, pointer.get(0)));
                long sampledResources = SamplerResources.activeResources(compiler, stack);
                SamplerResources.check(spvc_compiler_create_shader_resources(compiler, pointer));
                long resources = pointer.get(0);
                int model = stage == GL20.GL_VERTEX_SHADER ? SpvExecutionModelVertex : SpvExecutionModelFragment;
                SamplerResources.check(spvc_compiler_create_compiler_options(compiler, pointer));
                long options = pointer.get(0);
                SamplerResources.check(
                        spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_MSL_VERSION, 20300));
                SamplerResources.check(spvc_compiler_options_set_uint(
                        options, SPVC_COMPILER_OPTION_MSL_PLATFORM, SPVC_MSL_PLATFORM_MACOS));
                SamplerResources.check(
                        spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_MSL_ARGUMENT_BUFFERS, true));
                SamplerResources.check(
                        spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_MSL_ARGUMENT_BUFFERS_TIER, 1));
                SamplerResources.check(
                        spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true));
                SamplerResources.check(spvc_compiler_options_set_bool(
                        options, SPVC_COMPILER_OPTION_MSL_FORCE_ACTIVE_ARGUMENT_BUFFER_RESOURCES, true));
                SamplerResources.check(spvc_compiler_install_compiler_options(compiler, options));
                for (int set = 0; set < 4; set++)
                    SamplerResources.check(spvc_compiler_msl_add_discrete_descriptor_set(compiler, set));
                var count = stack.pointers(0);
                SamplerResources.check(spvc_resources_get_resource_list_for_type(
                        sampledResources, SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, pointer, count));
                var images = SpvcReflectedResource.create(pointer.get(0), Math.toIntExact(count.get(0)));
                for (var image : images) {
                    int binding = spvc_compiler_get_decoration(compiler, image.id(), SpvDecorationBinding);
                    int width = SamplerResources.width(compiler, image);
                    if (binding < 0 || binding + width > SamplerResources.LIMIT)
                        throw new UnsupportedOperationException("Sampled resource binding exceeds argument table");
                    spvc_compiler_set_decoration(compiler, image.id(), SpvDecorationDescriptorSet, 4);
                    var map = SpvcMslResourceBinding2.calloc(stack);
                    spvc_msl_resource_binding_init_2(map);
                    map.stage(model)
                            .desc_set(4)
                            .binding(binding)
                            .count(width)
                            .msl_texture(binding)
                            .msl_sampler(32 + binding);
                    SamplerResources.check(spvc_compiler_msl_add_resource_binding_2(compiler, map));
                }
                var argument = SpvcMslResourceBinding2.calloc(stack);
                spvc_msl_resource_binding_init_2(argument);
                argument.stage(model)
                        .desc_set(4)
                        .binding(SPVC_MSL_ARGUMENT_BUFFER_BINDING)
                        .msl_buffer(13);
                SamplerResources.check(spvc_compiler_msl_add_resource_binding_2(compiler, argument));
                int ubos = bind(compiler, resources, SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, model, 0, stack);
                bind(compiler, resources, SPVC_RESOURCE_TYPE_STORAGE_BUFFER, model, ubos, stack);
                bind(compiler, resources, SPVC_RESOURCE_TYPE_STORAGE_IMAGE, model, -1, stack);
                bind(compiler, resources, SPVC_RESOURCE_TYPE_SEPARATE_IMAGE, model, -1, stack);
                SamplerResources.check(spvc_compiler_compile(compiler, pointer));
                String code = "// julia sampler arguments v1\n" + MemoryUtil.memUTF8(pointer.get(0));
                String entry = spvc_compiler_get_cleansed_entry_point_name(compiler, "main", model);
                return new Output(MemoryUtil.memUTF8(code, true), entry == null ? "main0" : entry);
            } catch (RuntimeException failure) {
                throw new UnsupportedOperationException(
                        "Metal sampled-resource translation failed: " + spvc_context_get_last_error_string(context),
                        failure);
            } finally {
                spvc_context_destroy(context);
            }
        }
    }

    private static int bind(long compiler, long resources, int kind, int model, int offset, MemoryStack stack) {
        var pointer = stack.pointers(0);
        var count = stack.pointers(0);
        SamplerResources.check(spvc_resources_get_resource_list_for_type(resources, kind, pointer, count));
        var list = SpvcReflectedResource.create(pointer.get(0), Math.toIntExact(count.get(0)));
        int i = 0;
        for (var resource : list) {
            int set = spvc_compiler_get_decoration(compiler, resource.id(), SpvDecorationDescriptorSet);
            int binding = spvc_compiler_get_decoration(compiler, resource.id(), SpvDecorationBinding);
            var map = SpvcMslResourceBinding2.calloc(stack);
            spvc_msl_resource_binding_init_2(map);
            map.stage(model).desc_set(set).binding(binding).count(1);
            if (offset < 0) map.msl_texture(binding);
            else map.msl_buffer(kind == SPVC_RESOURCE_TYPE_UNIFORM_BUFFER ? binding : offset + i);
            SamplerResources.check(spvc_compiler_msl_add_resource_binding_2(compiler, map));
            i++;
        }
        if (offset >= 0 && offset + i > 13)
            throw new UnsupportedOperationException("Shader buffer bindings collide with sampler arguments");
        return i;
    }
}
