package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.opengl.GL42.glTexStorage2D;
import static org.lwjgl.opengl.GL43.glTextureView;
import static org.lwjgl.opengl.GL45.glTextureParameteri;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Builds a floating-point depth pyramid by sampling each preceding mip through base/max-level changes. */
final class TextureMipChecks {
    private static final int WIDTH = 32;
    private static final int HEIGHT = 16;
    private static final String VERTEX = """
            #version 330 core
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(p * 2.0 - 1.0, 0, 1);
            }
            """;

    private TextureMipChecks() {}

    static void run(Path output) throws IOException {
        int source = glGenTextures(), target = glGenTextures(), other = glGenTextures(), view = glGenTextures();
        int fbo = glGenFramebuffers(), sampler = glGenSamplers();
        int fill = program("""
                #version 330 core
                out float value;
                void main() { value = 4.0 + gl_FragCoord.x + 8.0 * gl_FragCoord.y; }
                """);
        int down = program("""
                #version 330 core
                uniform sampler2D source;
                out float value;
                void main() {
                    ivec2 p = ivec2(gl_FragCoord.xy) * 2;
                    value = (texelFetch(source, p, 0).r + texelFetch(source, p + ivec2(1, 0), 0).r
                           + texelFetch(source, p + ivec2(0, 1), 0).r
                           + texelFetch(source, p + ivec2(1, 1), 0).r) * 0.25;
                }
                """);
        int verify = program("""
                #version 330 core
                uniform sampler2D source;
                uniform ivec2 levels;
                uniform ivec2 size;
                uniform int absoluteBase;
                out vec4 color;
                float expected(ivec2 p, int level) {
                    return 4.0 + (float(p.x) + 0.5) * exp2(float(level))
                               + (float(p.y) + 0.5) * exp2(float(level)) * 8.0;
                }
                void main() {
                    ivec2 p = ivec2(gl_FragCoord.xy);
                    vec2 uv = (vec2(p) + 0.5) / vec2(size);
                    int relative = levels.y - levels.x;
                    ivec2 farSize = max(size >> relative, ivec2(1));
                    float nearValue = expected(p, absoluteBase + levels.x);
                    float farValue = expected(ivec2(uv * vec2(farSize)), absoluteBase + levels.y);
                    bool fetch = abs(texelFetch(source, p, 0).r - nearValue) < 0.01;
                    bool sampleNear = abs(textureLod(source, uv, 0.0).r - nearValue) < 0.01;
                    bool sampleFar = abs(textureLod(source, uv, 100.0).r - farValue) < 0.01;
                    bool dimensions = all(equal(textureSize(source, 0), size));
                    color = vec4(fetch, sampleNear, sampleFar, dimensions);
                }
                """);
        int pixels = 0;
        try {
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, source);
            glTexStorage2D(GL_TEXTURE_2D, 5, GL_R16F, WIDTH, HEIGHT);
            nearest();
            glBindTexture(GL_TEXTURE_2D, target);
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, WIDTH, HEIGHT);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glDisable(GL_BLEND);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_CULL_FACE);
            glDisable(GL_SCISSOR_TEST);
            draw(source, 0, fill, WIDTH, HEIGHT);
            glBindTexture(GL_TEXTURE_2D, source);
            // The last target bound on this unit must not redirect a 2D texture parameter to a 3D name.
            glBindTexture(GL_TEXTURE_3D, other);
            for (int level = 1; level < 5; level++) {
                glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_BASE_LEVEL, level - 1);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, level - 1);
                if (glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_BASE_LEVEL) != level - 1
                        || glGetTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL) != level - 1)
                    throw new IllegalStateException("Texture mip parameters were lost: level=" + level + " base="
                            + glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_BASE_LEVEL) + " max="
                            + glGetTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL));
                draw(source, level, down, WIDTH >> level, Math.max(1, HEIGHT >> level));
            }
            glSamplerParameteri(sampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);
            glSamplerParameteri(sampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glBindSampler(0, sampler);
            for (int base = 0; base < 5; base++) {
                glTextureParameteri(source, GL_TEXTURE_BASE_LEVEL, base);
                for (int max = base; max < 5; max++) {
                    glTextureParameteri(source, GL_TEXTURE_MAX_LEVEL, max);
                    pixels += verify(target, verify, base, max, 0);
                }
            }
            // Changing a view's own base level must remain relative to the view, and independent of its parent.
            glTextureView(view, GL_TEXTURE_2D, source, GL_R16F, 1, 3, 0, 1);
            glBindTexture(GL_TEXTURE_2D, view);
            nearest();
            glTextureParameteri(view, GL_TEXTURE_BASE_LEVEL, 1);
            glTextureParameteri(view, GL_TEXTURE_MAX_LEVEL, 2);
            pixels += verify(target, verify, 1, 2, 1);
            glDeleteTextures(source);
            source = 0;
            pixels += verify(target, verify, 1, 2, 1);
            // Immutable storage clamps the effective range while preserving the requested parameter values.
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_BASE_LEVEL, 99);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);
            if (glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_BASE_LEVEL) != 99)
                throw new IllegalStateException("Immutable mip clamp changed the requested GL state");
            pixels += verify(target, verify, 2, 2, 1);
            glDeleteTextures(view);
            view = 0;
            // A mutable reallocation must retire the cached sampling view and preserve the object's parameters.
            source = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, source);
            nearest();
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);
            for (int attempt = 0; attempt < 2; attempt++) {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_R32F, WIDTH, HEIGHT, 0, GL_RED, GL_FLOAT, (ByteBuffer) null);
                glTexImage2D(GL_TEXTURE_2D, 1, GL_R32F, WIDTH / 2, HEIGHT / 2, 0, GL_RED, GL_FLOAT, (ByteBuffer) null);
                draw(source, 0, fill, WIDTH, HEIGHT);
                pixels += verify(target, verify, 0, 0, 0);
            }
            Files.writeString(
                    output.resolve("texture-mips.txt"),
                    "PASS\nverified_pixels=" + pixels
                            + "\nchecks=half-float depth pyramid,base-relative texelFetch/textureSize/textureLod,max-level clamp,sampler objects,bound/named/float setters,target independence,view-relative levels,parent deletion,immutable clamp,mutable reallocation\n");
        } finally {
            glBindSampler(0, 0);
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            for (int texture : new int[] {source, target, other, view}) glDeleteTextures(texture);
            for (int program : new int[] {fill, down, verify}) glDeleteProgram(program);
            glDeleteFramebuffers(fbo);
            glDeleteSamplers(sampler);
        }
    }

    private static void nearest() {
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }

    private static void draw(int texture, int level, int program, int width, int height) {
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, level);
        glViewport(0, 0, width, height);
        glUseProgram(program);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    private static int verify(int target, int program, int base, int max, int absoluteBase) {
        int width = WIDTH >> (base + absoluteBase), height = Math.max(1, HEIGHT >> (base + absoluteBase));
        glUseProgram(program);
        glUniform2i(glGetUniformLocation(program, "levels"), base, max);
        glUniform2i(glGetUniformLocation(program, "size"), width, height);
        glUniform1i(glGetUniformLocation(program, "absoluteBase"), absoluteBase);
        draw(target, 0, program, width, height);
        byte[] data = ResourceReadback.layer(target, 0);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                for (int c = 0; c < 4; c++)
                    if ((data[(y * WIDTH + x) * 4 + c] & 255) != 255)
                        throw new IllegalStateException("Mip sampling mismatch: range=" + base + ".." + max
                                + " absoluteBase=" + absoluteBase + " pixel=" + x + "," + y + " check=" + c);
        return width * height;
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
