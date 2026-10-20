package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.*;
import static org.lwjgl.opengl.GL43.GL_TEXTURE_BUFFER_OFFSET_ALIGNMENT;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Real texel fetches test shared stores, ranges, storage replacement, deletion, and large texture-buffer widths. */
final class BufferTextureChecks {
    private BufferTextureChecks() {}

    static void run(Path output) throws IOException {
        Files.deleteIfExists(output.resolve("buffer-textures.txt"));
        int vertex = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vertex, """
                #version 330 core
                void main() {
                    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                    gl_Position = vec4(p * 2.0 - 1.0, 0, 1);
                }
                """);
        compile(vertex);
        int target = glGenTextures(), fbo = glGenFramebuffers();
        glActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_2D, target);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 8, 8, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
        glViewport(0, 0, 8, 8);
        glDisable(GL_BLEND);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glDisable(GL_SCISSOR_TEST);
        try {
            int[][] formats = {
                {GL_R32F, GL_RG32F, GL_RGBA32F}, {GL_R32I, GL_RG32I, GL_RGBA32I}, {GL_R32UI, GL_RG32UI, GL_RGBA32UI}
            };
            for (int kind = 0; kind < 3; kind++)
                for (int components = 0; components < 3; components++)
                    check(vertex, target, formats[kind][components], 1 << components, kind);
            unsignedShorts(vertex, target);
            large(vertex, target);
            compute();
            ordering(vertex, target, false);
            ordering(vertex, target, true);
            vertexStage(target);
            sharedUnit(vertex, target);
            emptyStore(vertex, target);
            Files.writeString(
                    output.resolve("buffer-textures.txt"),
                    "PASS\nverified_pixels=3264\nverified_compute_words=4\nlimit_texels="
                            + glGetInteger(GL_MAX_TEXTURE_BUFFER_SIZE) + "\noffset_alignment="
                            + glGetInteger(GL_TEXTURE_BUFFER_OFFSET_ALIGNMENT)
                            + "\nchecks=ten formats,unsigned 16-bit channels,vertex/fragment/compute,reflection,ranges,updates,reallocation,deletion,pending draws,separate bindings,shared sampler units,empty and sub-texel stores,million texels\n");
        } finally {
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_BUFFER, 0);
            glBindBuffer(GL_TEXTURE_BUFFER, 0);
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glActiveTexture(GL_TEXTURE3);
            glBindTexture(GL_TEXTURE_2D, 0);
            glActiveTexture(GL_TEXTURE0);
            glDeleteFramebuffers(fbo);
            glDeleteTextures(target);
            glDeleteShader(vertex);
        }
    }

    private static int program(int vertex, int kind) {
        int fragment = glCreateShader(GL_FRAGMENT_SHADER), program = glCreateProgram();
        String sampler = kind == 0 ? "samplerBuffer" : kind == 1 ? "isamplerBuffer" : "usamplerBuffer";
        String value = kind == 0
                ? "texelFetch(inputData, index).rgb"
                : kind == 1
                        ? "vec3(abs(texelFetch(inputData, index).rgb)) / 255.0"
                        : "vec3(texelFetch(inputData, index).rgb & uvec3(255u)) / 255.0";
        glShaderSource(
                fragment,
                "#version 330 core\nuniform " + sampler
                        + " inputData;\nuniform int index;\nout vec4 color;\nvoid main() { color = vec4(" + value
                        + ", 1); }\n");
        compile(fragment);
        glAttachShader(program, vertex);
        glAttachShader(program, fragment);
        glDeleteShader(fragment);
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
            throw new IllegalStateException(glGetProgramInfoLog(program));
        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program, "inputData"), 0);
        try (var stack = MemoryStack.stackPush()) {
            var size = stack.mallocInt(1);
            var type = stack.mallocInt(1);
            boolean found = false;
            for (int index = 0; index < glGetProgrami(program, GL_ACTIVE_UNIFORMS); index++) {
                if (!glGetActiveUniform(program, index, 128, size, type).equals("inputData")) continue;
                int expected = kind == 0
                        ? GL_SAMPLER_BUFFER
                        : kind == 1 ? GL_INT_SAMPLER_BUFFER : GL_UNSIGNED_INT_SAMPLER_BUFFER;
                if (type.get(0) != expected) throw new IllegalStateException("Buffer sampler reflection lost its type");
                found = true;
            }
            if (!found) throw new IllegalStateException("Buffer sampler reflection lost its name");
        }
        return program;
    }

    private static void check(int vertex, int target, int format, int components, int kind) {
        int program = program(vertex, kind), texture = glGenTextures(), buffer = glGenBuffers();
        int alignment = glGetInteger(GL_TEXTURE_BUFFER_OFFSET_ALIGNMENT);
        var data = MemoryUtil.memAlloc(alignment + components * 8).order(ByteOrder.nativeOrder());
        try {
            for (int i = 0; i < data.capacity(); i++) data.put(i, (byte) 0);
            for (int texel = 0; texel < 2; texel++)
                for (int c = 0; c < components; c++) {
                    int offset = alignment + (texel * components + c) * 4;
                    if (kind == 0) data.putFloat(offset, (c + 1) * 0.2f);
                    else data.putInt(offset, kind == 1 ? -(c + 1) * 51 : 0x80000000 | (c + 1) * 51);
                }
            glBindBuffer(GL_TEXTURE_BUFFER, buffer);
            glBufferData(GL_TEXTURE_BUFFER, data, GL_DYNAMIC_DRAW);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_BUFFER, texture);
            GL43.glTexBufferRange(GL_TEXTURE_BUFFER, format, buffer, alignment, components * 8L);
            // Ordinary textures can remain bound to the same unit without replacing the buffer texture.
            glBindTexture(GL_TEXTURE_2D, target);
            if (!glIsTexture(texture)
                    || glGetInteger(GL_TEXTURE_BINDING_BUFFER) != texture
                    || glGetInteger(GL_TEXTURE_BINDING_2D) != target
                    || glGetInteger(GL_TEXTURE_BUFFER) != buffer
                    || glGetTexLevelParameteri(GL_TEXTURE_BUFFER, 0, GL_TEXTURE_WIDTH) != 2)
                throw new IllegalStateException("Buffer texture bindings or range size mismatch");
            reject(() -> GL43.glTexBufferRange(GL_TEXTURE_BUFFER, format, buffer, 1, components * 4L));
            reject(() -> GL43.glTexBufferRange(GL_TEXTURE_BUFFER, format, buffer, alignment, data.capacity() + 1L));
            reject(() -> GL31.glTexBuffer(GL_TEXTURE_BUFFER, GL_RGB32F, buffer));
            reject(() -> glBindTexture(GL_TEXTURE_2D, texture));
            reject(() -> GL42.glBindImageTexture(0, texture, 0, false, 0, GL_READ_ONLY, GL_R32UI));
            draw(target, 51, components >= 2 ? 102 : 0, components >= 4 ? 153 : 0);
            data.position(alignment).limit(alignment + components * 4);
            if (kind == 0) data.putFloat(alignment, 0.4f);
            else data.putInt(alignment, kind == 1 ? -102 : 0x80000066);
            glBufferSubData(GL_TEXTURE_BUFFER, alignment, data);
            draw(target, 102, components >= 2 ? 102 : 0, components >= 4 ? 153 : 0);
            data.clear();
            glBufferData(GL_TEXTURE_BUFFER, data, GL_DYNAMIC_DRAW);
            draw(target, 102, components >= 2 ? 102 : 0, components >= 4 ? 153 : 0);
            glDeleteBuffers(buffer);
            if (glIsBuffer(buffer)) throw new IllegalStateException("Deleted buffer name remained live");
            int replacement = glGenBuffers();
            glBindBuffer(GL_TEXTURE_BUFFER, replacement);
            glBufferData(GL_TEXTURE_BUFFER, data.capacity(), GL_DYNAMIC_DRAW);
            draw(target, 102, components >= 2 ? 102 : 0, components >= 4 ? 153 : 0);
            glDeleteBuffers(replacement);
        } finally {
            MemoryUtil.memFree(data);
            glBindBuffer(GL_TEXTURE_BUFFER, 0);
            glBindTexture(GL_TEXTURE_BUFFER, 0);
            glDeleteTextures(texture);
            glDeleteBuffers(buffer);
            glUseProgram(0);
            glDeleteProgram(program);
        }
    }

    private static void unsignedShorts(int vertex, int target) {
        int fragment = glCreateShader(GL_FRAGMENT_SHADER), program = glCreateProgram();
        int buffer = glGenBuffers(), texture = glGenTextures();
        int alignment = glGetInteger(GL_TEXTURE_BUFFER_OFFSET_ALIGNMENT);
        var data = MemoryUtil.memAlloc(alignment + 16).order(ByteOrder.nativeOrder());
        try {
            glShaderSource(fragment, """
                    #version 330 core
                    uniform usamplerBuffer inputData;
                    uniform int index;
                    uniform int phase;
                    out vec4 color;
                    void main() {
                        uvec4 expected = index == 0 ? uvec4(0u,32767u,32768u,65535u)
                            : phase == 0 ? uvec4(65535u,32768u,1u,0u) : uvec4(7u,40000u,65534u,12345u);
                        color = all(equal(texelFetch(inputData,index),expected)) ? vec4(.2,.4,.6,1) : vec4(0,0,0,1);
                    }
                    """);
            compile(fragment);
            glAttachShader(program, vertex);
            glAttachShader(program, fragment);
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
                throw new IllegalStateException(glGetProgramInfoLog(program));
            glUseProgram(program);
            glUniform1i(glGetUniformLocation(program, "inputData"), 0);
            int index = glGetUniformLocation(program, "index"), phase = glGetUniformLocation(program, "phase");
            for (int i = 0; i < data.capacity(); i++) data.put(i, (byte) 0x7b);
            int[] initial = {0, 32767, 32768, 65535, 65535, 32768, 1, 0};
            for (int i = 0; i < initial.length; i++) data.putShort(alignment + i * 2, (short) initial[i]);
            glBindBuffer(GL_TEXTURE_BUFFER, buffer);
            glBufferData(GL_TEXTURE_BUFFER, data, GL_DYNAMIC_DRAW);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_BUFFER, texture);
            GL43.glTexBufferRange(GL_TEXTURE_BUFFER, GL_RGBA16UI, buffer, alignment, 16);
            if (glGetTexLevelParameteri(GL_TEXTURE_BUFFER, 0, GL_TEXTURE_WIDTH) != 2
                    || glGetTexLevelParameteri(GL_TEXTURE_BUFFER, 0, GL_TEXTURE_INTERNAL_FORMAT) != GL_RGBA16UI)
                throw new IllegalStateException("Unsigned-short buffer texture metadata lost its 8-byte texel layout");
            draw(target, 51, 102, 153);
            glUniform1i(index, 1);
            draw(target, 51, 102, 153);
            int[] changed = {7, 40000, 65534, 12345};
            for (int i = 0; i < changed.length; i++) data.putShort(alignment + 8 + i * 2, (short) changed[i]);
            data.position(alignment + 8).limit(alignment + 16);
            glBufferSubData(GL_TEXTURE_BUFFER, alignment + 8L, data);
            glUniform1i(phase, 1);
            draw(target, 51, 102, 153);
            data.clear();
            glBufferData(GL_TEXTURE_BUFFER, data, GL_DYNAMIC_DRAW);
            draw(target, 51, 102, 153);
            GL31.glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA16UI, buffer);
            if (glGetTexLevelParameteri(GL_TEXTURE_BUFFER, 0, GL_TEXTURE_WIDTH) != data.capacity() / 8)
                throw new IllegalStateException("Full unsigned-short view has the wrong texel count");
            glUniform1i(index, alignment / 8 + 1);
            draw(target, 51, 102, 153);
        } finally {
            MemoryUtil.memFree(data);
            glUseProgram(0);
            glBindTexture(GL_TEXTURE_BUFFER, 0);
            glBindBuffer(GL_TEXTURE_BUFFER, 0);
            glDeleteTextures(texture);
            glDeleteBuffers(buffer);
            glDeleteProgram(program);
            glDeleteShader(fragment);
        }
    }

    private static void large(int vertex, int target) {
        int program = program(vertex, 2), texture = glGenTextures(), buffer = glGenBuffers();
        int texels = 1 << 20;
        var value = MemoryUtil.memAlloc(4).order(ByteOrder.nativeOrder());
        value.putInt(0, 0x80000033);
        try {
            glBindBuffer(GL_TEXTURE_BUFFER, buffer);
            glBufferData(GL_TEXTURE_BUFFER, texels * 4L, GL_STATIC_DRAW);
            glBufferSubData(GL_TEXTURE_BUFFER, (texels - 1) * 4L, value);
            glBindTexture(GL_TEXTURE_BUFFER, texture);
            GL31.glTexBuffer(GL_TEXTURE_BUFFER, GL_R32UI, buffer);
            if (glGetTexLevelParameteri(GL_TEXTURE_BUFFER, 0, GL_TEXTURE_WIDTH) != texels)
                throw new IllegalStateException("Large buffer texture was limited to ordinary image dimensions");
            glUniform1i(glGetUniformLocation(program, "index"), texels - 1);
            draw(target, 51, 0, 0);
        } finally {
            MemoryUtil.memFree(value);
            glBindBuffer(GL_TEXTURE_BUFFER, 0);
            glBindTexture(GL_TEXTURE_BUFFER, 0);
            glDeleteTextures(texture);
            glDeleteBuffers(buffer);
            glUseProgram(0);
            glDeleteProgram(program);
        }
    }

    private static void ordering(int vertex, int target, boolean replace) {
        int program = program(vertex, 0), texture = glGenTextures(), buffer = glGenBuffers();
        int bufferTarget = replace ? GL43.GL_SHADER_STORAGE_BUFFER : GL_ARRAY_BUFFER;
        var data = MemoryUtil.memAlloc(16).order(ByteOrder.nativeOrder());
        data.putFloat(0, 0.2f).putFloat(4, 0.4f).putFloat(8, 0.6f).putFloat(12, 1);
        try {
            glBindBuffer(bufferTarget, buffer);
            glBufferData(bufferTarget, data, GL_DYNAMIC_DRAW);
            glBindTexture(GL_TEXTURE_BUFFER, texture);
            GL31.glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32F, buffer);
            glViewport(0, 0, 4, 8);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            data.putFloat(0, 0.6f);
            if (replace) glBufferData(bufferTarget, data, GL_DYNAMIC_DRAW);
            else {
                data.limit(4);
                glBufferSubData(bufferTarget, 0, data);
            }
            glViewport(4, 0, 4, 8);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            // Deleting both GL names before readback must leave both queued draws intact.
            glDeleteTextures(texture);
            glDeleteBuffers(buffer);
            var pixels = ResourceReadback.layer(target, 0);
            for (int y = 0; y < 8; y++)
                for (int x = 0; x < 8; x++) {
                    int[] expected = {x < 4 ? 51 : 153, 102, 153, 255};
                    for (int c = 0; c < 4; c++)
                        if ((pixels[(y * 8 + x) * 4 + c] & 255) != expected[c])
                            throw new IllegalStateException(
                                    "Buffer texture draw/update ordering mismatch at " + x + "," + y + " channel " + c);
                }
        } finally {
            MemoryUtil.memFree(data);
            glViewport(0, 0, 8, 8);
            glBindBuffer(bufferTarget, 0);
            glBindTexture(GL_TEXTURE_BUFFER, 0);
            glDeleteTextures(texture);
            glDeleteBuffers(buffer);
            glUseProgram(0);
            glDeleteProgram(program);
        }
    }

    private static void vertexStage(int target) {
        int vertex = glCreateShader(GL_VERTEX_SHADER),
                fragment = glCreateShader(GL_FRAGMENT_SHADER),
                program = glCreateProgram();
        int texture = glGenTextures(), buffer = glGenBuffers();
        var data = MemoryUtil.memAlloc(16).order(ByteOrder.nativeOrder());
        data.putFloat(0, 0.2f).putFloat(4, 0.4f).putFloat(8, 0.6f).putFloat(12, 1);
        try {
            glShaderSource(vertex, """
                    #version 330 core
                    uniform samplerBuffer inputData;
                    out vec3 shade;
                    void main() {
                        vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                        gl_Position = vec4(p * 2.0 - 1.0, 0, 1);
                        shade = texelFetch(inputData, 0).rgb;
                    }
                    """);
            glShaderSource(
                    fragment,
                    "#version 330 core\nin vec3 shade;\nout vec4 color;\nvoid main() { color = vec4(shade, 1); }\n");
            compile(vertex);
            compile(fragment);
            glAttachShader(program, vertex);
            glAttachShader(program, fragment);
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
                throw new IllegalStateException(glGetProgramInfoLog(program));
            glUseProgram(program);
            glUniform1i(glGetUniformLocation(program, "inputData"), 0);
            glBindBuffer(GL_TEXTURE_BUFFER, buffer);
            glBufferData(GL_TEXTURE_BUFFER, data, GL_STATIC_DRAW);
            glBindTexture(GL_TEXTURE_BUFFER, texture);
            GL31.glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32F, buffer);
            draw(target, 51, 102, 153);
        } finally {
            MemoryUtil.memFree(data);
            glBindBuffer(GL_TEXTURE_BUFFER, 0);
            glBindTexture(GL_TEXTURE_BUFFER, 0);
            glDeleteTextures(texture);
            glDeleteBuffers(buffer);
            glUseProgram(0);
            glDeleteProgram(program);
            glDeleteShader(vertex);
            glDeleteShader(fragment);
        }
    }

    /** glTexBuffer accepts stores smaller than one texel; the texture has width zero and reads as zero until it grows. */
    private static void emptyStore(int vertex, int target) {
        int program = program(vertex, 2), texture = glGenTextures(), buffer = glGenBuffers();
        var data = MemoryUtil.memAlloc(8).order(ByteOrder.nativeOrder());
        data.putShort(0, (short) 51)
                .putShort(2, (short) 102)
                .putShort(4, (short) 153)
                .putShort(6, (short) 0);
        try {
            glBindBuffer(GL_TEXTURE_BUFFER, buffer);
            glBufferData(GL_TEXTURE_BUFFER, 0L, GL_STATIC_DRAW);
            glBindTexture(GL_TEXTURE_BUFFER, texture);
            GL31.glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA16UI, buffer);
            if (glGetTexLevelParameteri(GL_TEXTURE_BUFFER, 0, GL_TEXTURE_WIDTH) != 0)
                throw new IllegalStateException("Empty buffer texture reported texels");
            draw(target, 0, 0, 0);
            glBufferData(GL_TEXTURE_BUFFER, 4L, GL_STATIC_DRAW);
            if (glGetTexLevelParameteri(GL_TEXTURE_BUFFER, 0, GL_TEXTURE_WIDTH) != 0)
                throw new IllegalStateException("Sub-texel buffer texture reported texels");
            draw(target, 0, 0, 0);
            glBufferData(GL_TEXTURE_BUFFER, data, GL_STATIC_DRAW);
            if (glGetTexLevelParameteri(GL_TEXTURE_BUFFER, 0, GL_TEXTURE_WIDTH) != 1)
                throw new IllegalStateException("Grown buffer texture lost its texel");
            draw(target, 51, 102, 153);
        } finally {
            MemoryUtil.memFree(data);
            glBindBuffer(GL_TEXTURE_BUFFER, 0);
            glBindTexture(GL_TEXTURE_BUFFER, 0);
            glDeleteTextures(texture);
            glDeleteBuffers(buffer);
            glUseProgram(0);
            glDeleteProgram(program);
        }
    }

    /** Desktop drivers draw when sampler types share a unit and read each target's own binding there. */
    private static void sharedUnit(int vertex, int target) {
        int fragment = glCreateShader(GL_FRAGMENT_SHADER), program = glCreateProgram();
        int image = glGenTextures(), texture = glGenTextures(), buffer = glGenBuffers();
        var pixel = MemoryUtil.memAlloc(4);
        var data = MemoryUtil.memAlloc(16).order(ByteOrder.nativeOrder());
        pixel.put(0, (byte) 51).put(1, (byte) 102).put(2, (byte) 153).put(3, (byte) 255);
        data.putFloat(0, 0.8f).putFloat(4, 0).putFloat(8, 0).putFloat(12, 0);
        try {
            glShaderSource(fragment, """
                    #version 330 core
                    uniform sampler2D uTexture;
                    uniform samplerBuffer uData;
                    uniform isamplerBuffer uIndices;
                    uniform int uUse;
                    out vec4 color;
                    void main() {
                        color = texture(uTexture, vec2(0.5));
                        if (uUse != 0) color.r = texelFetch(uData, 0).r;
                        if (uUse > 1) color.g = float(texelFetch(uIndices, 0).x);
                    }
                    """);
            compile(fragment);
            glAttachShader(program, vertex);
            glAttachShader(program, fragment);
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
                throw new IllegalStateException(glGetProgramInfoLog(program));
            glUseProgram(program);
            // Only uTexture is assigned; both buffer samplers stay on their default unit 0.
            glUniform1i(glGetUniformLocation(program, "uTexture"), 0);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, image);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
            draw(target, 51, 102, 153);
            glBindBuffer(GL_TEXTURE_BUFFER, buffer);
            glBufferData(GL_TEXTURE_BUFFER, data, GL_STATIC_DRAW);
            glBindTexture(GL_TEXTURE_BUFFER, texture);
            GL31.glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32F, buffer);
            glUniform1i(glGetUniformLocation(program, "uUse"), 1);
            draw(target, 204, 102, 153);
        } finally {
            MemoryUtil.memFree(pixel);
            MemoryUtil.memFree(data);
            glBindBuffer(GL_TEXTURE_BUFFER, 0);
            glBindTexture(GL_TEXTURE_BUFFER, 0);
            glBindTexture(GL_TEXTURE_2D, 0);
            glDeleteTextures(texture);
            glDeleteTextures(image);
            glDeleteBuffers(buffer);
            glUseProgram(0);
            glDeleteProgram(program);
            glDeleteShader(fragment);
        }
    }

    private static void reject(Runnable operation) {
        try {
            operation.run();
        } catch (IllegalArgumentException | UnsupportedOperationException expected) {
            return;
        }
        throw new IllegalStateException("Invalid buffer texture operation was accepted");
    }

    private static void compute() {
        int shader = glCreateShader(GL43.GL_COMPUTE_SHADER), program = glCreateProgram();
        int input = glGenBuffers(), output = glGenBuffers(), texture = glGenTextures();
        var bytes = MemoryUtil.memAlloc(16).order(ByteOrder.nativeOrder());
        int[] values = {17, 31, 255, 0x80000000};
        for (int i = 0; i < values.length; i++) bytes.putInt(i * 4, values[i]);
        try {
            glShaderSource(shader, """
                    #version 430 core
                    layout(local_size_x=1) in;
                    uniform usamplerBuffer inputData;
                    layout(std430, binding=1) buffer Result { uvec4 value; };
                    void main() { value = texelFetch(inputData, 0); }
                    """);
            compile(shader);
            glAttachShader(program, shader);
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
                throw new IllegalStateException(glGetProgramInfoLog(program));
            glUseProgram(program);
            glUniform1i(glGetUniformLocation(program, "inputData"), 0);
            glActiveTexture(GL_TEXTURE0);
            glBindBuffer(GL_TEXTURE_BUFFER, input);
            glBufferData(GL_TEXTURE_BUFFER, bytes, GL_STATIC_DRAW);
            glBindTexture(GL_TEXTURE_BUFFER, texture);
            GL31.glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32UI, input);
            glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, output);
            glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, 16L, GL_DYNAMIC_DRAW);
            glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, output);
            GL43.glDispatchCompute(1, 1, 1);
            GL43.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL43.GL_BUFFER_UPDATE_BARRIER_BIT);
            glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, bytes);
            for (int i = 0; i < values.length; i++)
                if (bytes.getInt(i * 4) != values[i])
                    throw new IllegalStateException("Compute buffer texture changed component " + i);
        } finally {
            MemoryUtil.memFree(bytes);
            glUseProgram(0);
            glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, 0);
            glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
            glBindTexture(GL_TEXTURE_BUFFER, 0);
            glBindBuffer(GL_TEXTURE_BUFFER, 0);
            glDeleteTextures(texture);
            glDeleteBuffers(input);
            glDeleteBuffers(output);
            glDeleteProgram(program);
            glDeleteShader(shader);
        }
    }

    private static void compile(int shader) {
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
            throw new IllegalStateException(glGetShaderInfoLog(shader));
    }

    private static void draw(int target, int r, int g, int b) {
        glDrawArrays(GL_TRIANGLES, 0, 3);
        var pixels = ResourceReadback.layer(target, 0);
        int[] expected = {r, g, b, 255};
        for (int i = 0; i < 64; i++)
            for (int c = 0; c < 4; c++)
                if ((pixels[i * 4 + c] & 255) != expected[c])
                    throw new IllegalStateException("Buffer texture pixel " + i + " channel " + c + " got "
                            + (pixels[i * 4 + c] & 255) + " expected " + expected[c]);
    }
}
