package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.function.Consumer;

/** Array resource ownership, MRT routing, and integer/matrix uniforms verified by actual GPU pixels. */
final class ArrayApiChecks {
    private ArrayApiChecks() {}

    static void run() {
        int[] textures = {-1, -1};
        Consumer<int[]> generate = org.lwjgl.opengl.GL11::glGenTextures;
        generate.accept(textures);
        if (textures[0] <= 0 || textures[1] <= 0 || textures[0] == textures[1])
            throw new IllegalStateException("Texture array generation failed");
        int[] framebuffers = {glGenFramebuffers()};
        int vertex = glCreateShader(GL_VERTEX_SHADER), fragment = glCreateShader(GL_FRAGMENT_SHADER);
        int program = glCreateProgram();
        try {
            glShaderSource(vertex, """
                    #version 330 core
                    void main() {
                        vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                        gl_Position = vec4(p * 2.0 - 1.0, 0, 1);
                    }
                    """);
            glShaderSource(fragment, """
                    #version 330 core
                    uniform int values[2];
                    uniform mat3 basis;
                    layout(location = 0) out vec4 first;
                    layout(location = 1) out vec4 second;
                    void main() {
                        first = vec4(basis * vec3(float(values[0]) / 10.0, float(values[1]) / 10.0, 0.6), 1);
                        second = vec4(0.8, 0.2, 0.4, 1);
                    }
                    """);
            for (int shader : new int[] {vertex, fragment}) {
                glCompileShader(shader);
                if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
                    throw new IllegalStateException(glGetShaderInfoLog(shader));
                glAttachShader(program, shader);
            }
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
                throw new IllegalStateException(glGetProgramInfoLog(program));
            glUseProgram(program);
            glUniform1iv(glGetUniformLocation(program, "values"), new int[] {2, 4});
            // Row-major cyclic permutation with an incomplete trailing matrix to ignore.
            glUniformMatrix3fv(
                    glGetUniformLocation(program, "basis"), true, new float[] {0, 1, 0, 0, 0, 1, 1, 0, 0, 99, 98});
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffers[0]);
            for (int i = 0; i < textures.length; i++) {
                glBindTexture(GL_TEXTURE_2D, textures[i]);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 8, 8, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + i, GL_TEXTURE_2D, textures[i], 0);
            }
            glViewport(0, 0, 8, 8);
            glDisable(GL_BLEND);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_CULL_FACE);
            glDisable(GL_SCISSOR_TEST);
            int[] attachments = {GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1};
            int[] original = attachments.clone();
            glDrawBuffers(attachments);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Incomplete array API framebuffer");
            glDrawArrays(GL_TRIANGLES, 0, 3);
            verify(textures[0], 102, 153, 51);
            verify(textures[1], 204, 51, 102);
            if (!Arrays.equals(attachments, original))
                throw new IllegalStateException("Draw buffer selection changed its input array");
            glDrawBuffers(new int[0]);
            glDrawBuffers(attachments);
        } finally {
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glBindTexture(GL_TEXTURE_2D, 0);
            Consumer<int[]> delete = org.lwjgl.opengl.GL11::glDeleteTextures;
            delete.accept(textures);
            glDeleteFramebuffers(framebuffers);
            glDeleteProgram(program);
            glDeleteShader(vertex);
            glDeleteShader(fragment);
        }
        if (glIsTexture(textures[0]) || glIsTexture(textures[1]) || glIsFramebuffer(framebuffers[0]))
            throw new IllegalStateException("Array deletion retained a live resource");
        glGenTextures(new int[0]);
        glDeleteTextures(new int[] {0});
        glDeleteFramebuffers(new int[] {0});
    }

    private static void verify(int texture, int r, int g, int b) {
        var pixels = ResourceReadback.layer(texture, 0);
        int[] expected = {r, g, b, 255};
        for (int i = 0; i < 64; i++)
            for (int c = 0; c < 4; c++)
                if ((pixels[i * 4 + c] & 255) != expected[c])
                    throw new IllegalStateException("Array API pixel " + i + " channel " + c + " got "
                            + (pixels[i * 4 + c] & 255) + " expected " + expected[c]);
    }
}
