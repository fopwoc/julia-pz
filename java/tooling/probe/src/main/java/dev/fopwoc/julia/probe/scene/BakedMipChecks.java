package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL42.glTexStorage2D;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ViewPoint's tree atlas: sprites are blended into level 0 of a cleared, transparent render target, mipmaps are
 * regenerated right after each bake, and distant trees sample the small levels. Lost alpha there shows up as black
 * boxes around far trees only.
 */
final class BakedMipChecks {
    private static final int SIZE = 64;
    private static final int LEVELS = 7;
    private static final String VERTEX = """
            #version 330 core
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(p * 2.0 - 1.0, 0, 1);
            }
            """;

    private BakedMipChecks() {}

    static void run(Path output) throws IOException {
        int atlas = glGenTextures(), target = glGenTextures();
        int bakeFbo = glGenFramebuffers(), sampleFbo = glGenFramebuffers();
        int sprite = program("""
                #version 330 core
                uniform vec3 tint;
                out vec4 color;
                void main() { color = vec4(tint, 1.0); }
                """);
        int sample = program("""
                #version 330 core
                uniform sampler2D atlas;
                out vec4 color;
                void main() {
                    int i = int(gl_FragCoord.x);
                    vec2 uv = i == 0 ? vec2(0.25, 0.5) : i == 1 ? vec2(0.25, 0.5) : i == 2 ? vec2(0.75, 0.25)
                                     : vec2(0.75, 0.75);
                    color = textureLod(atlas, uv, i == 0 ? 6.0 : 3.0);
                }
                """);
        try {
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, atlas);
            glTexStorage2D(GL_TEXTURE_2D, LEVELS, GL_RGBA8, SIZE, SIZE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, LEVELS - 1);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glBindTexture(GL_TEXTURE_2D, target);
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, 4, 1);
            glBindTexture(GL_TEXTURE_2D, 0);
            glBindFramebuffer(GL_FRAMEBUFFER, bakeFbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, atlas, 0);
            glBindFramebuffer(GL_FRAMEBUFFER, sampleFbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_CULL_FACE);

            glBindFramebuffer(GL_FRAMEBUFFER, bakeFbo);
            glViewport(0, 0, SIZE, SIZE);
            glClearColor(0, 0, 0, 0);
            glClear(GL_COLOR_BUFFER_BIT);
            // Left half: one opaque white sprite. The atlas average is then half covered.
            bake(atlas, sprite, 0, 0, SIZE / 2, SIZE, 1, 1, 1);
            int[] first = sample(atlas, target, sampleFbo, sample);
            expect("first bake, whole-atlas level", first, 0, 128, 128, 128, 128);
            expect("first bake, sprite at level 3", first, 1, 255, 255, 255, 255);
            expect("first bake, empty slot at level 3", first, 2, 0, 0, 0, 0);

            // A later frame bakes into an empty slot and regenerates the chain over already-sampled levels.
            glBindFramebuffer(GL_FRAMEBUFFER, bakeFbo);
            glViewport(0, 0, SIZE, SIZE);
            bake(atlas, sprite, SIZE / 2, SIZE / 2, SIZE / 2, SIZE / 2, 0, 1, 0);
            int[] second = sample(atlas, target, sampleFbo, sample);
            expect("second bake, whole-atlas level", second, 0, 128, 191, 128, 191);
            expect("second bake, old sprite at level 3", second, 1, 255, 255, 255, 255);
            expect("second bake, still empty at level 3", second, 2, 0, 0, 0, 0);
            expect("second bake, new sprite at level 3", second, 3, 0, 255, 0, 255);
            Files.writeString(
                    output.resolve("baked-mips.txt"),
                    "PASS\nchecks=blended bake into cleared level 0,generateMipmap after render,small-level alpha,"
                            + "incremental rebake\n");
        } finally {
            glDisable(GL_BLEND);
            glDisable(GL_SCISSOR_TEST);
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            for (int texture : new int[] {atlas, target}) glDeleteTextures(texture);
            for (int program : new int[] {sprite, sample}) glDeleteProgram(program);
            glDeleteFramebuffers(bakeFbo);
            glDeleteFramebuffers(sampleFbo);
        }
    }

    private static void bake(int atlas, int program, int x, int y, int width, int height, float r, float g, float b) {
        glEnable(GL_SCISSOR_TEST);
        glScissor(x, y, width, height);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glUseProgram(program);
        glUniform3f(glGetUniformLocation(program, "tint"), r, g, b);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_BLEND);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glBindTexture(GL_TEXTURE_2D, atlas);
        glGenerateMipmap(GL_TEXTURE_2D);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    private static int[] sample(int atlas, int target, int fbo, int program) {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, 4, 1);
        glBindTexture(GL_TEXTURE_2D, atlas);
        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program, "atlas"), 0);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindTexture(GL_TEXTURE_2D, 0);
        byte[] data = ResourceReadback.layer(target, 0);
        int[] values = new int[16];
        for (int i = 0; i < values.length; i++) values[i] = data[i] & 255;
        return values;
    }

    private static void expect(String check, int[] pixels, int index, int r, int g, int b, int a) {
        int[] want = {r, g, b, a};
        for (int c = 0; c < 4; c++)
            if (Math.abs(pixels[index * 4 + c] - want[c]) > 4)
                throw new IllegalStateException("Baked mip mismatch (" + check + "): got rgba "
                        + pixels[index * 4] + "," + pixels[index * 4 + 1] + "," + pixels[index * 4 + 2] + ","
                        + pixels[index * 4 + 3] + " expected " + r + "," + g + "," + b + "," + a);
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
