package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL43;

/** Initializers survive linking and relinking without changing another bound program or its pending deletion. */
final class UniformInitializerChecks {
    private UniformInitializerChecks() {}

    static void run() {
        int vs = glCreateShader(GL_VERTEX_SHADER), fs = glCreateShader(GL_FRAGMENT_SHADER);
        int program = glCreateProgram(), other = glCreateProgram();
        int target = glGenTextures(), fbo = glGenFramebuffers();
        try {
            glShaderSource(vs, """
                    #version 330 core
                    void main() {
                        vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                        gl_Position = vec4(p * 2.0 - 1.0, 0, 1);
                    }
                    """);
            glShaderSource(fs, """
                    #version 330 core
                    uniform int useTexture = 1;
                    uniform int selection = -3;
                    uniform uint mask = 7u;
                    uniform bool enabled = true;
                    uniform vec3 shade = vec3(0.2, 0.4, 0.6);
                    uniform float gain = 2.0;
                    uniform float weights[2] = float[2](0.25, 0.25);
                    uniform mat2 basis = mat2(0.0, 1.0, 1.0, 0.0);
                    out vec4 color;
                    void main() {
                        vec3 rgb = vec3(basis * shade.rg, shade.b) * gain * (weights[0] + weights[1]);
                        color = useTexture == 1 && enabled && selection == -3 && mask == 7u
                                ? vec4(rgb, 1.0) : vec4(1.0);
                    }
                    """);
            for (int shader : new int[] {vs, fs}) {
                glCompileShader(shader);
                if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
                    throw new IllegalStateException(glGetShaderInfoLog(shader));
                glAttachShader(program, shader);
                glAttachShader(other, shader);
            }
            link(other);
            glUseProgram(other);
            glUniform1i(glGetUniformLocation(other, "useTexture"), 0);
            glDeleteProgram(other);
            link(program);
            if (glGetInteger(GL_CURRENT_PROGRAM) != other || !glIsProgram(other))
                throw new IllegalStateException("Link initialization changed or released the current program");
            if (GL43.glGetUniformi(program, glGetUniformLocation(program, "useTexture")) != 1
                    || GL43.glGetUniformi(program, glGetUniformLocation(program, "selection")) != -3
                    || GL43.glGetUniformf(program, glGetUniformLocation(program, "gain")) != 2.0f)
                throw new IllegalStateException("Uniform queries did not expose link-time initializers");
            glBindTexture(GL_TEXTURE_2D, target);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 8, 8, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Incomplete initializer framebuffer");
            glViewport(0, 0, 8, 8);
            glDisable(GL_BLEND);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_CULL_FACE);
            glDisable(GL_SCISSOR_TEST);
            draw(target, 255, 255, 255);
            glUseProgram(program);
            if (glIsProgram(other)) throw new IllegalStateException("Pending program survived unbinding");
            draw(target, 102, 51, 153);
            glUniform1i(glGetUniformLocation(program, "useTexture"), 0);
            draw(target, 255, 255, 255);
            link(program);
            if (glGetInteger(GL_CURRENT_PROGRAM) != program)
                throw new IllegalStateException("Relink changed the bound program");
            draw(target, 102, 51, 153);
        } finally {
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glBindTexture(GL_TEXTURE_2D, 0);
            glDeleteFramebuffers(fbo);
            glDeleteTextures(target);
            glDeleteProgram(program);
            glDeleteProgram(other);
            glDeleteShader(vs);
            glDeleteShader(fs);
        }
    }

    private static void link(int program) {
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
            throw new IllegalStateException(glGetProgramInfoLog(program));
    }

    private static void draw(int target, int r, int g, int b) {
        glDrawArrays(GL_TRIANGLES, 0, 3);
        var pixels = ResourceReadback.layer(target, 0);
        int[] expected = {r, g, b, 255};
        for (int i = 0; i < 64; i++)
            for (int c = 0; c < 4; c++)
                if ((pixels[i * 4 + c] & 255) != expected[c])
                    throw new IllegalStateException("Uniform initializer pixel " + i + " channel " + c + " got "
                            + (pixels[i * 4 + c] & 255) + " expected " + expected[c]);
    }
}
