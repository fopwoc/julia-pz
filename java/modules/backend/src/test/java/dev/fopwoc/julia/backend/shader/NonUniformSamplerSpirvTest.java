package dev.fopwoc.julia.backend.shader;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.util.spvc.Spv.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class NonUniformSamplerSpirvTest {
    @Test
    void decoratesOnlyThePerPrimitiveAtlasAccess() {
        var code = instructions(NonUniformSamplerSpirv.translate(compile("""
                #version 450
                layout(binding = 0) uniform sampler2D atlases[4];
                layout(location = 0) flat in int atlas;
                layout(location = 0) out vec4 color;
                void main() { color = texture(atlases[atlas], vec2(0.5)) + texture(atlases[1], vec2(0.5)); }
                """)));
        var nonUniform = code.stream()
                .filter(i -> i[0] == SpvOpDecorate && i[2] == SpvDecorationNonUniform)
                .map(i -> i[1])
                .toList();
        var chains = new HashMap<Integer, int[]>();
        for (var i : code) if (i[0] == SpvOpAccessChain) chains.put(i[2], i);
        var constants =
                code.stream().filter(i -> i[0] == SpvOpConstant).map(i -> i[2]).toList();
        var dynamic = chains.values().stream()
                .filter(i -> !constants.contains(i[4]))
                .findFirst()
                .orElseThrow()[2];
        var fixed = chains.values().stream()
                .filter(i -> constants.contains(i[4]))
                .findFirst()
                .orElseThrow()[2];
        assertTrue(nonUniform.contains(dynamic));
        assertFalse(nonUniform.contains(fixed));
        var dynamicLoad = code.stream()
                .filter(i -> i[0] == SpvOpLoad && i[3] == dynamic)
                .findFirst()
                .orElseThrow()[2];
        assertTrue(nonUniform.contains(dynamicLoad), "the loaded image is what drivers inspect");
        assertTrue(code.stream()
                .anyMatch(i -> i[0] == SpvOpCapability && i[1] == SpvCapabilitySampledImageArrayNonUniformIndexing));
        assertTrue(code.stream().anyMatch(i -> i[0] == SpvOpCapability && i[1] == SpvCapabilityShaderNonUniform));
        assertTrue(code.stream().anyMatch(i -> i[0] == SpvOpExtension));
    }

    @Test
    void followsTheAtlasIntoHelperFunctions() {
        // ViewPoint's shell passes uAtlases[vAtlas] to sampleSprite(); the sample loads the image from a parameter.
        var code = instructions(NonUniformSamplerSpirv.translate(compile("""
                #version 450
                layout(binding = 0) uniform sampler2D atlases[4];
                layout(location = 0) flat in int atlas;
                layout(location = 0) out vec4 color;
                vec4 sampleSprite(sampler2D image) { return texture(image, vec2(0.5)); }
                void main() { color = sampleSprite(atlases[atlas]); }
                """)));
        var parameter = code.stream()
                .filter(i -> i[0] == SpvOpFunctionParameter)
                .findFirst()
                .orElseThrow()[2];
        var helperLoad = code.stream()
                .filter(i -> i[0] == SpvOpLoad && i[3] == parameter)
                .findFirst()
                .orElseThrow()[2];
        var nonUniform = code.stream()
                .filter(i -> i[0] == SpvOpDecorate && i[2] == SpvDecorationNonUniform)
                .map(i -> i[1])
                .toList();
        assertTrue(nonUniform.contains(helperLoad));
        assertFalse(nonUniform.contains(parameter), "decorations belong on values, not parameters");
    }

    @Test
    void leavesUniformlyIndexedShadersUntouched() {
        var source = compile("""
                #version 450
                layout(binding = 0) uniform sampler2D atlases[4];
                layout(location = 0) out vec4 color;
                void main() { color = texture(atlases[2], vec2(0.5)); }
                """);
        assertSame(source, NonUniformSamplerSpirv.translate(source));
    }

    static ByteBuffer compile(String glsl) {
        long compiler = shaderc_compiler_initialize();
        long result = shaderc_compile_into_spv(compiler, glsl, shaderc_glsl_fragment_shader, "test.frag", "main", 0);
        try {
            if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success)
                fail(shaderc_result_get_error_message(result));
            var bytes = shaderc_result_get_bytes(result);
            var copy = ByteBuffer.allocateDirect(bytes.remaining()).order(ByteOrder.LITTLE_ENDIAN);
            return copy.put(bytes).flip();
        } finally {
            shaderc_result_release(result);
            shaderc_compiler_release(compiler);
        }
    }

    private static List<int[]> instructions(ByteBuffer code) {
        var words = code.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        var result = new ArrayList<int[]>();
        words.position(5);
        while (words.hasRemaining()) {
            int first = words.get(words.position()), length = first >>> 16;
            int[] instruction = new int[length];
            words.get(instruction);
            instruction[0] = first & 65535;
            result.add(instruction);
        }
        return result;
    }
}
