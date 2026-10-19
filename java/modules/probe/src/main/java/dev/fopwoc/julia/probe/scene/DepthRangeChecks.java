package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

import dev.fopwoc.julia.backend.BackendInternals;
import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryStack;

/** PZ's out-of-range camera depth parameters must clamp before reaching the native viewport. */
final class DepthRangeChecks {
    private DepthRangeChecks() {}

    static void run() {
        int vs = glCreateShader(GL_VERTEX_SHADER), fs = glCreateShader(GL_FRAGMENT_SHADER), program = glCreateProgram();
        int target = glGenTextures(), depth = glGenTextures(), fbo = glGenFramebuffers();
        try (var stack = MemoryStack.stackPush()) {
            glShaderSource(vs, """
                    #version 330 core
                    uniform float z;
                    void main() {
                        vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                        gl_Position = vec4(p * 2.0 - 1.0, z, 1);
                    }
                    """);
            glShaderSource(fs, """
                    #version 330 core
                    uniform vec4 tint;
                    out vec4 color;
                    void main() { color = tint; }
                    """);
            for (int shader : new int[] {vs, fs}) {
                glCompileShader(shader);
                if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
                    throw new IllegalStateException(glGetShaderInfoLog(shader));
                glAttachShader(program, shader);
            }
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
                throw new IllegalStateException(glGetProgramInfoLog(program));
            glUseProgram(program);
            glBindTexture(GL_TEXTURE_2D, target);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 8, 8, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            glBindTexture(GL_TEXTURE_2D, depth);
            glTexImage2D(
                    GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT32F, 8, 8, 0, GL_DEPTH_COMPONENT, GL_FLOAT, (ByteBuffer) null);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depth, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Incomplete depth-range framebuffer");
            glViewport(0, 0, 8, 8);
            glDisable(GL_BLEND);
            glDisable(GL_CULL_FACE);
            glDisable(GL_SCISSOR_TEST);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LESS);
            glDepthMask(true);
            glClearDepth(1.0);
            int z = glGetUniformLocation(program, "z"), tint = glGetUniformLocation(program, "tint");
            for (boolean outside : new boolean[] {false, true}) {
                glDepthRange(outside ? -10 : 0, outside ? 10 : 1);
                var range = stack.mallocDouble(2);
                glGetDoublev(GL_DEPTH_RANGE, range);
                var state = BackendInternals.context();
                if (range.get(0) != 0
                        || range.get(1) != 1
                        || state.viewportDepthNear != 0
                        || state.viewportDepthFar != 1)
                    throw new IllegalStateException("Camera depth range was not clamped: GL=" + range.get(0) + ","
                            + range.get(1) + " backend=" + state.viewportDepthNear + "," + state.viewportDepthFar);
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                glUniform1f(z, -0.25f);
                glUniform4f(tint, 1, 0, 0, 1);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                glUniform1f(z, -0.5f);
                glUniform4f(tint, 0, 1, 0, 1);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                var pixels = ResourceReadback.layer(target, 0);
                for (int i = 0; i < 64; i++)
                    for (int c = 0; c < 4; c++) {
                        int expected = c == 1 || c == 3 ? 255 : 0;
                        if ((pixels[i * 4 + c] & 255) != expected)
                            throw new IllegalStateException("Near surface failed depth test: outside=" + outside
                                    + " pixel=" + i + " channel=" + c + " got=" + (pixels[i * 4 + c] & 255));
                    }
            }
        } finally {
            glDepthRange(0, 1);
            glDisable(GL_DEPTH_TEST);
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glBindTexture(GL_TEXTURE_2D, 0);
            glDeleteFramebuffers(fbo);
            glDeleteTextures(target);
            glDeleteTextures(depth);
            glDeleteProgram(program);
            glDeleteShader(vs);
            glDeleteShader(fs);
        }
    }
}
