package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryStack;

/** Conditional legacy shaders with a sampler whose name collides with modern texture functions. */
final class ShaderCompatibilityChecks {
    private ShaderCompatibilityChecks() {}

    static void run() {
        int vertex = glCreateShader(GL_VERTEX_SHADER), fragment = glCreateShader(GL_FRAGMENT_SHADER);
        int program = glCreateProgram(), image = glGenTextures(), target = glGenTextures(), fbo = glGenFramebuffers();
        try (var stack = MemoryStack.stackPush()) {
            glShaderSource(vertex, """
                    #version 330 core
                    void main() {
                        vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                        gl_Position = vec4(p * 2.0 - 1.0, 0, 1);
                    }
                    """);
            glShaderSource(fragment, """
                    #version 110
                    uniform sampler2D texture;
                    #if 1
                    uniform float amount;
                    #else
                    float amount = 0.0;
                    #endif
                    float strength() {
                    #ifdef QUALITY
                        return 0.0;
                    #else
                        return amount;
                    #endif
                    }
                    void main() { gl_FragColor = texture2D(texture, vec2(0.5)) * strength(); }
                    """);
            for (int shader : new int[] {vertex, fragment}) {
                glCompileShader(shader);
                if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
                    throw new IllegalStateException("Conditional compatibility shader: " + glGetShaderInfoLog(shader));
                glAttachShader(program, shader);
            }
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
                throw new IllegalStateException(glGetProgramInfoLog(program));
            glUseProgram(program);
            int sampler = glGetUniformLocation(program, "texture"), amount = glGetUniformLocation(program, "amount");
            if (sampler < 0 || amount < 0) throw new IllegalStateException("Legacy shader lost public uniform names");
            glUniform1i(sampler, 0);
            glUniform1f(amount, 1.0f);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, target);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 8, 8, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Incomplete compatibility framebuffer");
            glBindTexture(GL_TEXTURE_2D, image);
            glTexImage2D(
                    GL_TEXTURE_2D,
                    0,
                    GL_RGBA8,
                    1,
                    1,
                    0,
                    GL_RGBA,
                    GL_UNSIGNED_BYTE,
                    stack.bytes((byte) 51, (byte) 102, (byte) 153, (byte) 255));
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glViewport(0, 0, 8, 8);
            glDisable(GL_BLEND);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_CULL_FACE);
            glDisable(GL_SCISSOR_TEST);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            var pixels = ResourceReadback.layer(target, 0);
            int[] expected = {51, 102, 153, 255};
            for (int i = 0; i < 64; i++)
                for (int c = 0; c < 4; c++)
                    if ((pixels[i * 4 + c] & 255) != expected[c])
                        throw new IllegalStateException(
                                "Compatibility pixel " + i + " channel " + c + " got " + (pixels[i * 4 + c] & 255));
        } finally {
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glBindTexture(GL_TEXTURE_2D, 0);
            glDeleteFramebuffers(fbo);
            glDeleteTextures(target);
            glDeleteTextures(image);
            glDeleteProgram(program);
            glDeleteShader(vertex);
            glDeleteShader(fragment);
        }
    }
}
