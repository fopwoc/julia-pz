package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL43.*;

import dev.fopwoc.julia.backend.Subsystems;
import dev.fopwoc.julia.backend.Upstream;
import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.system.MemoryStack;

/** Queries a struct-array layout, uploads through those offsets, and proves the layout with a rendered draw. */
final class ProgramResourceChecks {
    private static final String VERTEX = """
            #version 430 core
            struct Instance { vec2 position; int selector; mat2x3 transform; float weights[2]; };
            layout(std430, binding = 3) readonly buffer InstancedData { Instance instances[2]; };
            out vec4 payload;
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
                payload = vec4(instances[1].position.x, instances[1].transform[1][2],
                               instances[1].weights[1], float(instances[1].selector) / 255.0);
            }
            """;
    private static final String FRAGMENT = """
            #version 430 core
            struct Instance { vec2 position; int selector; mat2x3 transform; float weights[2]; };
            layout(std430, binding = 3) readonly buffer InstancedData { Instance instances[2]; };
            in vec4 payload;
            layout(location = 0) out vec4 color;
            void main() { color = payload; color.r = instances[1].position.x; }
            """;

    private ProgramResourceChecks() {}

    static void run() {
        int program = glCreateProgram(), vs = glCreateShader(GL_VERTEX_SHADER), fs = glCreateShader(GL_FRAGMENT_SHADER);
        int buffer = glGenBuffers(), target = glGenTextures(), fbo = glGenFramebuffers();
        try (var stack = MemoryStack.stackPush()) {
            compile(vs, VERTEX);
            compile(fs, FRAGMENT);
            glAttachShader(program, vs);
            glAttachShader(program, fs);
            link(program);
            if (glGetProgramInterfacei(program, GL_SHADER_STORAGE_BLOCK, GL_ACTIVE_RESOURCES) != 1
                    || glGetProgramInterfacei(program, GL_SHADER_STORAGE_BLOCK, GL_MAX_NUM_ACTIVE_VARIABLES) != 4)
                throw new IllegalStateException("Storage interface resource/active-variable count mismatch");
            int block = glGetProgramResourceIndex(program, GL_SHADER_STORAGE_BLOCK, "InstancedData");
            if (block < 0 || glGetProgramResourceIndex(program, GL_SHADER_STORAGE_BLOCK, "absent") != -1) {
                throw new IllegalStateException("Storage-block index lookup mismatch");
            }
            var blocks = query(
                    program,
                    GL_SHADER_STORAGE_BLOCK,
                    block,
                    GL_BUFFER_BINDING,
                    GL_BUFFER_DATA_SIZE,
                    GL_NUM_ACTIVE_VARIABLES,
                    GL_REFERENCED_BY_VERTEX_SHADER,
                    GL_REFERENCED_BY_FRAGMENT_SHADER);
            if (blocks[0] != 3 || blocks[1] != 128 || blocks[2] != 4 || blocks[3] != 1 || blocks[4] != 1) {
                throw new IllegalStateException("Storage block metadata mismatch: " + Arrays.toString(blocks));
            }
            int[] variables = new int[4];
            int[] written = {-1};
            glGetProgramResourceiv(
                    program, GL_SHADER_STORAGE_BLOCK, block, new int[] {GL_ACTIVE_VARIABLES}, written, variables);
            if (written[0] != 4) throw new IllegalStateException("Active-variable count mismatch");
            int[] truncated = {-1, -1};
            glGetProgramResourceiv(
                    program,
                    GL_SHADER_STORAGE_BLOCK,
                    block,
                    new int[] {GL_ACTIVE_VARIABLES, GL_BUFFER_BINDING},
                    written,
                    truncated);
            if (written[0] != 2 || truncated[0] != variables[0] || truncated[1] != variables[1]) {
                throw new IllegalStateException("Resource property output truncation mismatch");
            }
            Map<String, int[]> layouts = new HashMap<>();
            for (int variable : variables) {
                int[] layout = query(
                        program,
                        GL_BUFFER_VARIABLE,
                        variable,
                        GL_NAME_LENGTH,
                        GL_TYPE,
                        GL_ARRAY_SIZE,
                        GL_OFFSET,
                        GL_ARRAY_STRIDE,
                        GL_MATRIX_STRIDE,
                        GL_TOP_LEVEL_ARRAY_SIZE,
                        GL_TOP_LEVEL_ARRAY_STRIDE,
                        GL_BLOCK_INDEX);
                String name = glGetProgramResourceName(program, GL_BUFFER_VARIABLE, variable, layout[0]);
                if (!glGetProgramResourceName(program, GL_BUFFER_VARIABLE, variable, 4)
                                .equals(name.substring(0, 3))
                        || !glGetProgramResourceName(program, GL_BUFFER_VARIABLE, variable, 0)
                                .isEmpty()
                        || glGetProgramResourceIndex(program, GL_BUFFER_VARIABLE, name) != variable) {
                    throw new IllegalStateException("Resource name lookup/truncation mismatch");
                }
                if (layout[6] != 2 || layout[7] != 64 || layout[8] != block) {
                    throw new IllegalStateException("Top-level struct array layout mismatch");
                }
                layouts.put(name, layout);
            }
            int[] position = require(layouts, "instances[0].position", GL_FLOAT_VEC2, 1, 0, 0, 0);
            int[] selector = require(layouts, "instances[0].selector", GL_INT, 1, 8, 0, 0);
            int[] transform = require(layouts, "instances[0].transform", GL_FLOAT_MAT2x3, 1, 16, 0, 16);
            int[] weights = require(layouts, "instances[0].weights[0]", GL_FLOAT, 2, 48, 4, 0);
            var bytes = stack.calloc(blocks[1]);
            int instance = position[7];
            bytes.putFloat(instance + position[3], 0.2f);
            bytes.putInt(instance + selector[3], 204);
            bytes.putFloat(instance + transform[3] + transform[5] + 2 * Float.BYTES, 0.4f);
            bytes.putFloat(instance + weights[3] + weights[4], 0.6f);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
            glBufferData(GL_SHADER_STORAGE_BUFFER, bytes, GL_STATIC_DRAW);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, blocks[0], buffer);
            glBindTexture(GL_TEXTURE_2D, target);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 8, 8, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Resource-query FBO incomplete");
            glViewport(0, 0, 8, 8);
            glDisable(GL_BLEND);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_SCISSOR_TEST);
            glUseProgram(program);
            var state = Upstream.context();
            var linked = Upstream.shaders().getProgram(program);
            if (state.boundSsboByIndex[3] != buffer
                    || linked.vertexGraphicsBindingMap.roSsboGlSlots().length != 1
                    || linked.vertexGraphicsBindingMap.roSsboGlSlots()[0] != 3) {
                throw new IllegalStateException(
                        "Storage buffer binding changed: " + state.boundSsboByIndex[3] + "/" + buffer);
            }
            long droppedBefore = Upstream.frames().frame().droppedDrawsThisFrame;
            glDrawArrays(GL_TRIANGLES, 0, 3);
            if (Upstream.frames().frame().droppedDrawsThisFrame != droppedBefore) {
                throw new IllegalStateException("Storage-buffer draw was dropped");
            }
            var pixels = ResourceReadback.layer(target, 0);
            for (int i = 0; i < 64; i++)
                for (int channel = 0; channel < 4; channel++) {
                    if ((pixels[i * 4 + channel] & 255) != (channel + 1) * 51) {
                        throw new IllegalStateException("Queried layout produced incorrect pixels at " + i + "/"
                                + channel + ": " + Arrays.toString(Arrays.copyOf(pixels, 16)) + "; colorMask="
                                + state.pipeline.colorWriteMask + "; cull=" + state.pipeline.cullEnabled);
                    }
                }
            glUseProgram(0);
            var object = Upstream.shaders().getProgram(program);
            var before = Subsystems.current().linkedPrograms().of(object).resources(object);
            compile(vs, VERTEX.replace("instances[2]", "instances[3]"));
            compile(fs, FRAGMENT.replace("instances[2]", "instances[3]"));
            link(program);
            int afterBlock = glGetProgramResourceIndex(program, GL_SHADER_STORAGE_BLOCK, "InstancedData");
            if (query(program, GL_SHADER_STORAGE_BLOCK, afterBlock, GL_BUFFER_DATA_SIZE)[0] != 192
                    || before
                            == Subsystems.current().linkedPrograms().of(object).resources(object)) {
                throw new IllegalStateException("Program resource cache survived a changed relink");
            }
        } finally {
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(fbo);
            glDeleteTextures(target);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, 0);
            glDeleteBuffers(buffer);
            glDeleteProgram(program);
            glDeleteShader(vs);
            glDeleteShader(fs);
        }
        computeLayout();
    }

    private static void computeLayout() {
        int program = glCreateProgram(), shader = glCreateShader(GL_COMPUTE_SHADER);
        try {
            compile(shader, """
                    #version 430 core
                    layout(local_size_x = 1) in;
                    layout(std430, binding = 7, row_major) readonly buffer Input {
                        mat2x3 transform;
                        vec4 values[];
                    } source;
                    layout(std430, binding = 9) writeonly buffer Output { float result; } destination;
                    void main() { destination.result = source.transform[1][2] + source.values[0].x; }
                    """);
            glAttachShader(program, shader);
            link(program);
            int input = glGetProgramResourceIndex(program, GL_SHADER_STORAGE_BLOCK, "Input");
            int output = glGetProgramResourceIndex(program, GL_SHADER_STORAGE_BLOCK, "Output");
            if (!Arrays.equals(
                            query(
                                    program,
                                    GL_SHADER_STORAGE_BLOCK,
                                    input,
                                    GL_BUFFER_BINDING,
                                    GL_BUFFER_DATA_SIZE,
                                    GL_REFERENCED_BY_COMPUTE_SHADER),
                            new int[] {7, 48, 1})
                    || query(program, GL_SHADER_STORAGE_BLOCK, output, GL_BUFFER_BINDING)[0] != 9) {
                throw new IllegalStateException("Compute read/write binding reflection mismatch");
            }
            int matrix = glGetProgramResourceIndex(program, GL_BUFFER_VARIABLE, "Input.transform");
            int array = glGetProgramResourceIndex(program, GL_BUFFER_VARIABLE, "Input.values");
            if (!Arrays.equals(
                            query(
                                    program,
                                    GL_BUFFER_VARIABLE,
                                    matrix,
                                    GL_TYPE,
                                    GL_OFFSET,
                                    GL_MATRIX_STRIDE,
                                    GL_IS_ROW_MAJOR),
                            new int[] {GL_FLOAT_MAT2x3, 0, 8, 1})
                    || !Arrays.equals(
                            query(
                                    program,
                                    GL_BUFFER_VARIABLE,
                                    array,
                                    GL_OFFSET,
                                    GL_ARRAY_SIZE,
                                    GL_ARRAY_STRIDE,
                                    GL_TOP_LEVEL_ARRAY_SIZE,
                                    GL_TOP_LEVEL_ARRAY_STRIDE),
                            new int[] {32, 0, 16, 0, 16})) {
                throw new IllegalStateException("Compute row-major/runtime array reflection mismatch");
            }
        } finally {
            glDeleteProgram(program);
            glDeleteShader(shader);
        }
    }

    private static int[] query(int program, int api, int index, int... properties) {
        int[] result = new int[properties.length];
        int[] count = {-1};
        glGetProgramResourceiv(program, api, index, properties, count, result);
        if (count[0] != result.length) throw new IllegalStateException("Resource property length mismatch");
        return result;
    }

    private static int[] require(
            Map<String, int[]> layouts,
            String name,
            int type,
            int size,
            int offset,
            int arrayStride,
            int matrixStride) {
        var value = layouts.get(name);
        if (value == null
                || value[1] != type
                || value[2] != size
                || value[3] != offset
                || value[4] != arrayStride
                || value[5] != matrixStride) {
            throw new IllegalStateException("Unexpected layout for " + name + ": " + layouts.keySet());
        }
        return value;
    }

    private static void compile(int shader, String source) {
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) throw new IllegalStateException(glGetShaderInfoLog(shader));
    }

    private static void link(int program) {
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == 0) throw new IllegalStateException(glGetProgramInfoLog(program));
    }
}
