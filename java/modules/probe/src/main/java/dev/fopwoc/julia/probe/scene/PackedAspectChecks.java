package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL42.glTexStorage2D;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import org.lwjgl.system.MemoryStack;

/**
 * A packed depth/stencil image attached through one aspect behaves as if the other buffer were absent: its test always
 * passes, its writes and clears do nothing, and its contents survive for framebuffers that attach both aspects.
 */
final class PackedAspectChecks {
    private PackedAspectChecks() {}

    static void run() {
        int image = glGenTextures(), color = glGenTextures();
        int both = glGenFramebuffers(), depthOnly = glGenFramebuffers(), stencilOnly = glGenFramebuffers();
        int program = program();
        try {
            glBindTexture(GL_TEXTURE_2D, image);
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_DEPTH24_STENCIL8, 16, 16);
            glBindTexture(GL_TEXTURE_2D, color);
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, 16, 16);
            glBindTexture(GL_TEXTURE_2D, 0);
            attach(both, color, GL_DEPTH_STENCIL_ATTACHMENT, image);
            attach(depthOnly, color, GL_DEPTH_ATTACHMENT, image);
            attach(stencilOnly, color, GL_STENCIL_ATTACHMENT, image);
            glViewport(0, 0, 16, 16);
            glDisable(GL_BLEND);
            glDisable(GL_CULL_FACE);
            glDisable(GL_SCISSOR_TEST);
            glDepthMask(true);
            glStencilMask(255);
            glUseProgram(program);

            glBindFramebuffer(GL_FRAMEBUFFER, both);
            glClearColor(0, 0, 0, 1);
            glClearDepth(1);
            glClearStencil(7);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);

            // Without a stencil buffer the stencil test passes and neither REPLACE nor a stencil clear lands.
            glBindFramebuffer(GL_FRAMEBUFFER, depthOnly);
            glClearStencil(99);
            glClear(GL_STENCIL_BUFFER_BIT);
            glEnable(GL_STENCIL_TEST);
            glStencilFunc(GL_NEVER, 200, 255);
            glStencilOp(GL_REPLACE, GL_REPLACE, GL_REPLACE);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LESS);
            draw(program, 0, 255, 0, 0);
            verify(color, 255, 0, 0, "depth-only draw ignoring the stencil test");

            // Without a depth buffer the depth test passes and the depth write is dropped; stencil still reads 7.
            glBindFramebuffer(GL_FRAMEBUFFER, stencilOnly);
            glClearDepth(0);
            glClear(GL_DEPTH_BUFFER_BIT);
            glDepthFunc(GL_NEVER);
            glStencilFunc(GL_EQUAL, 7, 255);
            glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
            draw(program, .8f, 0, 255, 0);
            verify(color, 0, 255, 0, "stencil-only draw ignoring the depth test");

            // The depth-only pass wrote 0.5; the stencil-only clear and its 0.9 write must not have landed.
            glBindFramebuffer(GL_FRAMEBUFFER, both);
            glDisable(GL_STENCIL_TEST);
            glDepthFunc(GL_LESS);
            draw(program, .2f, 0, 0, 255);
            verify(color, 0, 255, 0, "depth written through the depth-only framebuffer");
            draw(program, -.2f, 0, 0, 255);
            verify(color, 0, 0, 255, "depth below the depth-only write");
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glBindTexture(GL_TEXTURE_2D, image);
            try (var stack = MemoryStack.stackPush()) {
                var stencil = stack.calloc(256);
                glGetTexImage(GL_TEXTURE_2D, 0, GL_STENCIL_INDEX, GL_UNSIGNED_BYTE, stencil);
                for (int i = 0; i < 256; i++)
                    if ((stencil.get(i) & 255) != 7)
                        throw new IllegalStateException(
                                "Single-aspect stencil changed at " + i + ": " + (stencil.get(i) & 255));
            }
        } finally {
            glUseProgram(0);
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_DEPTH_TEST);
            glDepthFunc(GL_LESS);
            glStencilFunc(GL_ALWAYS, 0, 255);
            glClearDepth(1);
            glClearStencil(0);
            glBindTexture(GL_TEXTURE_2D, 0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(both);
            glDeleteFramebuffers(depthOnly);
            glDeleteFramebuffers(stencilOnly);
            glDeleteTextures(image);
            glDeleteTextures(color);
            glDeleteProgram(program);
        }
    }

    private static void attach(int fbo, int color, int attachment, int image) {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, attachment, GL_TEXTURE_2D, image, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("Single-aspect framebuffer incomplete: " + attachment);
    }

    private static void draw(int program, float z, int r, int g, int b) {
        glUniform1f(glGetUniformLocation(program, "z"), z);
        glUniform4f(glGetUniformLocation(program, "shade"), r / 255f, g / 255f, b / 255f, 1);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    private static void verify(int color, int r, int g, int b, String label) {
        byte[] pixels = ResourceReadback.layer(color, 0);
        int[] expected = {r, g, b, 255};
        for (int i = 0; i < 256; i++)
            for (int c = 0; c < 4; c++)
                if ((pixels[i * 4 + c] & 255) != expected[c])
                    throw new IllegalStateException(label + " mismatch at pixel " + i + " channel " + c + ": "
                            + (pixels[i * 4 + c] & 255) + " != " + expected[c]);
    }

    private static int program() {
        int program = glCreateProgram();
        int vs = shader(GL_VERTEX_SHADER, """
                #version 330 core
                uniform float z;
                void main() {
                    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                    gl_Position = vec4(p * 2.0 - 1.0, z, 1.0);
                }
                """);
        int fs = shader(GL_FRAGMENT_SHADER, """
                #version 330 core
                uniform vec4 shade;
                layout(location = 0) out vec4 color;
                void main() {
                    color = shade;
                }
                """);
        try {
            glAttachShader(program, vs);
            glAttachShader(program, fs);
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == 0)
                throw new IllegalStateException(glGetProgramInfoLog(program));
            return program;
        } finally {
            glDeleteShader(vs);
            glDeleteShader(fs);
        }
    }

    private static int shader(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) throw new IllegalStateException(glGetShaderInfoLog(shader));
        return shader;
    }
}
