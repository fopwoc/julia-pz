package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.nio.ByteBuffer;

/** UI draws must escape the previous model viewport while preserving existing content and stencil. */
final class UiViewportChecks {
    private UiViewportChecks() {}

    static void run() {
        int vs = glCreateShader(GL_VERTEX_SHADER), fs = glCreateShader(GL_FRAGMENT_SHADER), program = glCreateProgram();
        int target = glGenTextures(), depth = glGenTextures(), fbo = glGenFramebuffers();
        try {
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
                    GL_TEXTURE_2D, 0, GL_DEPTH24_STENCIL8, 8, 8, 0, GL_DEPTH_STENCIL, GL_UNSIGNED_INT_24_8, (ByteBuffer)
                            null);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_TEXTURE_2D, depth, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Incomplete UI framebuffer");
            glViewport(0, 0, 8, 8);
            glDisable(GL_BLEND);
            glDisable(GL_CULL_FACE);
            glDisable(GL_SCISSOR_TEST);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LESS);
            glDepthMask(true);
            glClearDepth(1.0);
            int z = glGetUniformLocation(program, "z"), tint = glGetUniformLocation(program, "tint");
            glClearStencil(1);
            glStencilMask(255);
            glClearColor(0, 0, 0, 0);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
            glEnable(GL_STENCIL_TEST);
            glStencilFunc(GL_EQUAL, 1, 255);
            glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
            glUniform1f(z, 0);
            glUniform4f(tint, 1, 0, 0, 1);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            // UI3DModel clears depth after the parent panel has drawn its text.
            glClear(GL_DEPTH_BUFFER_BIT);
            glPushAttrib(GL_ALL_ATTRIB_BITS);
            glViewport(2, 2, 4, 4);
            glUniform4f(tint, 0, 1, 0, 1);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            glPopAttrib();
            glDisable(GL_DEPTH_TEST);
            glViewport(6, 0, 2, 8);
            glUniform4f(tint, 0, 0, 1, 1);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            var pixels = ResourceReadback.layer(target, 0);
            for (int y = 0; y < 8; y++)
                for (int x = 0; x < 8; x++) {
                    int channel = x >= 6 ? 2 : x >= 2 && x < 6 && y >= 2 && y < 6 ? 1 : 0;
                    for (int c = 0; c < 4; c++) {
                        int expected = c == channel || c == 3 ? 255 : 0;
                        if ((pixels[(y * 8 + x) * 4 + c] & 255) != expected)
                            throw new IllegalStateException("UI viewport lost content or clipping at " + x + "," + y
                                    + " channel=" + c + " got=" + (pixels[(y * 8 + x) * 4 + c] & 255));
                    }
                }
        } finally {
            glDisable(GL_STENCIL_TEST);
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
