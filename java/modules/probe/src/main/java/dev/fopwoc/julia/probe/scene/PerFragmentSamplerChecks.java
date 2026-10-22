package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL42.glTexStorage2D;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.system.MemoryUtil;

/**
 * ViewPoint's distant shell: eight atlas pages bound from unit 64 up, picked per fragment through a helper function,
 * in a stage with about a dozen other samplers around the array. GL drivers honour the per-fragment index; on Vulkan it
 * needs NonUniform decorations, descriptor indexing, and correct per-element descriptors.
 */
final class PerFragmentSamplerChecks {
    private static final int COUNT = 8;
    private static final int FIRST_UNIT = 64;
    private static final int OTHERS = 11;
    private static final int WIDTH = 17;
    private static final String VERTEX = """
            #version 400 core
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(p * 2.0 - 1.0, 0, 1);
            }
            """;

    private PerFragmentSamplerChecks() {}

    static void run(Path output) throws IOException {
        int[] atlases = new int[COUNT];
        int other = glGenTextures(), target = glGenTextures(), fbo = glGenFramebuffers();
        int program = program("""
                #version 400 core
                uniform sampler2D before[5];
                uniform sampler2D atlases[8];
                uniform sampler2D after[6];
                out vec4 color;
                vec4 sampleSprite(sampler2D atlas) { return texture(atlas, vec2(0.5)); }
                void main() {
                    int x = int(gl_FragCoord.x);
                    vec4 others = vec4(0.0);
                    for (int i = 0; i < 5; i++) others += texture(before[i], vec2(0.5));
                    for (int i = 0; i < 6; i++) others += texture(after[i], vec2(0.5));
                    color = x < 16 ? sampleSprite(atlases[x % 8]) : others;
                }
                """);
        var texel = MemoryUtil.memAlloc(4);
        try {
            glActiveTexture(GL_TEXTURE0);
            for (int i = 0; i < COUNT; i++) {
                atlases[i] = glGenTextures();
                fill(atlases[i], texel, expected(i));
            }
            fill(other, texel, new int[] {10, 10, 10, 10});
            glBindTexture(GL_TEXTURE_2D, target);
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, WIDTH, 1);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            for (int i = 0; i < COUNT; i++) {
                glActiveTexture(GL_TEXTURE0 + FIRST_UNIT + i);
                glBindTexture(GL_TEXTURE_2D, atlases[i]);
            }
            for (int i = 0; i < OTHERS; i++) {
                glActiveTexture(GL_TEXTURE1 + i);
                glBindTexture(GL_TEXTURE_2D, other);
            }
            glActiveTexture(GL_TEXTURE0);
            glDisable(GL_BLEND);
            glDisable(GL_DEPTH_TEST);
            glViewport(0, 0, WIDTH, 1);
            glUseProgram(program);
            for (int i = 0; i < COUNT; i++)
                glUniform1i(glGetUniformLocation(program, "atlases[" + i + "]"), FIRST_UNIT + i);
            for (int i = 0; i < 5; i++) glUniform1i(glGetUniformLocation(program, "before[" + i + "]"), 1 + i);
            for (int i = 0; i < 6; i++) glUniform1i(glGetUniformLocation(program, "after[" + i + "]"), 6 + i);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            byte[] data = ResourceReadback.layer(target, 0);
            for (int x = 0; x < WIDTH; x++) {
                int[] want = x < 16 ? expected(x % COUNT) : new int[] {110, 110, 110, 110};
                for (int c = 0; c < 4; c++)
                    if (Math.abs((data[x * 4 + c] & 255) - want[c]) > 1)
                        throw new IllegalStateException("Per-fragment sampler mismatch at " + x + " channel " + c
                                + ": got " + (data[x * 4 + c] & 255) + " expected " + want[c]);
            }
            Files.writeString(
                    output.resolve("per-fragment-samplers.txt"),
                    "PASS\nchecks=per-fragment sampler-array index,helper-function sampler parameter,units 64+,"
                            + "19 samplers with the array mid-stage\n");
        } finally {
            MemoryUtil.memFree(texel);
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            for (int atlas : atlases) glDeleteTextures(atlas);
            glDeleteTextures(other);
            glDeleteTextures(target);
            glDeleteProgram(program);
            glDeleteFramebuffers(fbo);
        }
    }

    private static int[] expected(int atlas) {
        return new int[] {20 + atlas * 30, 240 - atlas * 25, atlas * 35, 255};
    }

    private static void fill(int texture, ByteBuffer texel, int[] rgba) {
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, 1, 1);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        for (int c = 0; c < 4; c++) texel.put(c, (byte) rgba[c]);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, texel);
    }

    private static int program(String fragment) {
        int program = glCreateProgram();
        for (int type : new int[] {GL_VERTEX_SHADER, GL_FRAGMENT_SHADER}) {
            int shader = glCreateShader(type);
            try {
                glShaderSource(shader, type == GL_VERTEX_SHADER ? VERTEX : fragment);
                glCompileShader(shader);
                if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0)
                    throw new IllegalStateException(glGetShaderInfoLog(shader));
                glAttachShader(program, shader);
            } finally {
                glDeleteShader(shader);
            }
        }
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == 0) throw new IllegalStateException(glGetProgramInfoLog(program));
        return program;
    }
}
