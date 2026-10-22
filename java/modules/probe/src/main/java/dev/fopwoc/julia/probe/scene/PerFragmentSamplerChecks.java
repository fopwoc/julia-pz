package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL42.glTexStorage2D;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.system.MemoryUtil;

/**
 * A sampler array indexed per fragment, through a helper function, as ViewPoint's distant shell does per sprite. GL
 * drivers honour this; on Vulkan it needs NonUniform decorations and descriptor indexing on the device.
 */
final class PerFragmentSamplerChecks {
    private static final int COUNT = 4;
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
        int target = glGenTextures(), fbo = glGenFramebuffers();
        int program = program("""
                #version 400 core
                uniform sampler2D atlases[4];
                out vec4 color;
                vec4 sampleSprite(sampler2D atlas) { return texture(atlas, vec2(0.5)); }
                void main() { color = sampleSprite(atlases[int(gl_FragCoord.x) % 4]); }
                """);
        var texel = MemoryUtil.memAlloc(4);
        try {
            for (int i = 0; i < COUNT; i++) {
                atlases[i] = glGenTextures();
                glActiveTexture(GL_TEXTURE0 + i);
                glBindTexture(GL_TEXTURE_2D, atlases[i]);
                glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, 1, 1);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                texel.put(0, (byte) (40 + i * 60))
                        .put(1, (byte) (200 - i * 50))
                        .put(2, (byte) (i * 80))
                        .put(3, (byte) 255);
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, texel);
            }
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, target);
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, 16, 1);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            for (int i = 0; i < COUNT; i++) {
                glActiveTexture(GL_TEXTURE0 + i);
                glBindTexture(GL_TEXTURE_2D, atlases[i]);
            }
            glActiveTexture(GL_TEXTURE0);
            glDisable(GL_BLEND);
            glDisable(GL_DEPTH_TEST);
            glViewport(0, 0, 16, 1);
            glUseProgram(program);
            for (int i = 0; i < COUNT; i++) glUniform1i(glGetUniformLocation(program, "atlases[" + i + "]"), i);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            byte[] data = ResourceReadback.layer(target, 0);
            for (int x = 0; x < 16; x++) {
                int i = x % COUNT;
                int[] want = {40 + i * 60, 200 - i * 50, i * 80, 255};
                for (int c = 0; c < 4; c++)
                    if ((data[x * 4 + c] & 255) != want[c])
                        throw new IllegalStateException("Per-fragment sampler mismatch at " + x + " channel " + c
                                + ": got " + (data[x * 4 + c] & 255) + " expected " + want[c]);
            }
            Files.writeString(
                    output.resolve("per-fragment-samplers.txt"),
                    "PASS\nchecks=per-fragment sampler-array index,helper-function sampler parameter\n");
        } finally {
            MemoryUtil.memFree(texel);
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            for (int atlas : atlases) glDeleteTextures(atlas);
            glDeleteTextures(target);
            glDeleteProgram(program);
            glDeleteFramebuffers(fbo);
        }
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
