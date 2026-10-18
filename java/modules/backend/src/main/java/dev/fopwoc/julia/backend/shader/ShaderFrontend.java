package dev.fopwoc.julia.backend.shader;

import static org.lwjgl.util.shaderc.GLSLang.*;
import static org.lwjgl.util.shaderc.GLSLangResourceLimits.glslang_default_resource;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.GLSLangInput;

/** Desktop GLSL object compilation and program linking, separate from Vulkan/Metal translation. */
public final class ShaderFrontend implements AutoCloseable {
    private static final Pattern BUILTIN_OVERRIDE =
            Pattern.compile("\\b(?:float|[iu]?vec[234]|int|uint)\\s+(?:min|max|clamp)\\s*\\(");

    public record Compiled(int type, String source, String log, boolean valid) {}

    private record Parsed(long handle, ByteBuffer code, String source, String log, boolean valid)
            implements AutoCloseable {
        @Override
        public void close() {
            glslang_shader_delete(handle);
            MemoryUtil.memFree(code);
        }
    }

    public record Linked(Map<Integer, String> stages, String log) {
        public boolean valid() {
            return log.isEmpty();
        }
    }

    public ShaderFrontend() {
        if (!glslang_initialize_process()) throw new IllegalStateException("Cannot initialize the GLSL frontend");
    }

    public Compiled compile(int type, String source) {
        try (var parsed = parse(type, source, false)) {
            return new Compiled(type, parsed.source(), parsed.log(), parsed.valid());
        }
    }

    private Parsed parse(int type, String source, boolean spirv) {
        var code = MemoryUtil.memUTF8(source);
        try (var stack = MemoryStack.stackPush()) {
            var input = GLSLangInput.calloc(stack)
                    .language(GLSLANG_SOURCE_GLSL)
                    .stage(stage(type))
                    .client(spirv ? GLSLANG_CLIENT_OPENGL : GLSLANG_CLIENT_NONE)
                    .client_version(spirv ? GLSLANG_TARGET_OPENGL_450 : 0)
                    .target_language(spirv ? GLSLANG_TARGET_SPV : GLSLANG_TARGET_NONE)
                    .target_language_version(spirv ? GLSLANG_TARGET_SPV_1_0 : 0)
                    .code(code)
                    .default_version(110)
                    .default_profile(GLSLANG_NO_PROFILE)
                    .messages(GLSLANG_MSG_DEFAULT_BIT)
                    .resource(glslang_default_resource());
            long shader = glslang_shader_create(input);
            if (shader == 0) throw new IllegalStateException("Cannot allocate GLSL shader object");
            try {
                if (spirv)
                    glslang_shader_set_options(
                            shader, GLSLANG_SHADER_AUTO_MAP_BINDINGS | GLSLANG_SHADER_AUTO_MAP_LOCATIONS);
                boolean valid = glslang_shader_preprocess(shader, input) && glslang_shader_parse(shader, input);
                String log = glslang_shader_get_info_log(shader);
                String preprocessed = glslang_shader_get_preprocessed_code(shader);
                return new Parsed(
                        shader, code, preprocessed == null ? source : preprocessed, log == null ? "" : log, valid);
            } catch (RuntimeException | Error failure) {
                glslang_shader_delete(shader);
                throw failure;
            }
        } catch (RuntimeException | Error failure) {
            MemoryUtil.memFree(code);
            throw failure;
        }
    }

    public Linked link(List<Compiled> shaders) {
        String validation = validate(shaders);
        if (!validation.isEmpty()) return new Linked(Map.of(), validation);
        var groups = new LinkedHashMap<Integer, List<Compiled>>();
        for (var shader : shaders)
            groups.computeIfAbsent(shader.type(), ignored -> new ArrayList<>()).add(shader);
        var stages = new LinkedHashMap<Integer, String>();
        for (var entry : groups.entrySet()) {
            var parts = entry.getValue();
            if (parts.size() == 1
                    && !BUILTIN_OVERRIDE.matcher(parts.getFirst().source()).find()) {
                stages.put(entry.getKey(), parts.getFirst().source());
                continue;
            }
            var translated = translate(parts, entry.getKey());
            if (!translated.valid()) return translated;
            stages.putAll(translated.stages());
        }
        return new Linked(Map.copyOf(stages), "");
    }

    private Linked translate(List<Compiled> shaders, int type) {
        var translated = new ArrayList<Parsed>();
        long program = glslang_program_create();
        if (program == 0) throw new IllegalStateException("Cannot allocate GLSL translation program");
        try {
            for (var shader : shaders) {
                var converted = parse(shader.type(), shader.source(), true);
                translated.add(converted);
                if (!converted.valid()) return new Linked(Map.of(), "OpenGL SPIR-V translation: " + converted.log());
                glslang_program_add_shader(program, converted.handle());
            }
            if (!glslang_program_link(program, GLSLANG_MSG_DEFAULT_BIT) || !glslang_program_map_io(program))
                return new Linked(Map.of(), "OpenGL SPIR-V link: " + glslang_program_get_info_log(program));
            glslang_program_SPIRV_generate(program, stage(type));
            var words = glslang_program_SPIRV_get_ptr(program);
            if (words == null || !words.hasRemaining())
                return new Linked(Map.of(), "No linked SPIR-V: " + glslang_program_SPIRV_get_messages(program));
            try {
                return new Linked(Map.of(type, LinkedShaderSource.generate(words)), "");
            } catch (IllegalStateException failure) {
                return new Linked(Map.of(), failure.getMessage());
            }
        } finally {
            // glslang programs must die before the shader intermediates they reference.
            glslang_program_delete(program);
            translated.forEach(Parsed::close);
        }
    }

    private String validate(List<Compiled> shaders) {
        if (shaders.isEmpty()) return "No shader objects attached";
        if (shaders.stream().anyMatch(shader -> !shader.valid())) return "Attached shader object did not compile";
        var parsed = new ArrayList<Parsed>();
        long program = glslang_program_create();
        if (program == 0) throw new IllegalStateException("Cannot allocate GLSL validation program");
        try {
            // Linking mutates glslang intermediates. Reparse immutable compile snapshots so
            // sharing a GL shader between programs or relinking cannot contaminate another link.
            for (var shader : shaders) {
                var part = parse(shader.type(), shader.source(), false);
                parsed.add(part);
                if (!part.valid()) return part.log();
                glslang_program_add_shader(program, part.handle());
            }
            return glslang_program_link(program, GLSLANG_MSG_DEFAULT_BIT) ? "" : glslang_program_get_info_log(program);
        } finally {
            glslang_program_delete(program);
            parsed.forEach(Parsed::close);
        }
    }

    private static int stage(int type) {
        return switch (type) {
            case GL20.GL_VERTEX_SHADER -> GLSLANG_STAGE_VERTEX;
            case GL20.GL_FRAGMENT_SHADER -> GLSLANG_STAGE_FRAGMENT;
            case GL32.GL_GEOMETRY_SHADER -> GLSLANG_STAGE_GEOMETRY;
            case GL43.GL_COMPUTE_SHADER -> GLSLANG_STAGE_COMPUTE;
            default -> throw new IllegalArgumentException("Unsupported shader stage: " + type);
        };
    }

    @Override
    public void close() {
        glslang_finalize_process();
    }
}
