package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL42.glTexStorage2D;
import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.opengl.GL44.*;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import org.lwjgl.system.MemoryStack;

/** Full-image view aliasing and integer stencil sampling through ordinary redirected OpenGL calls. */
final class ViewChecks {
    private ViewChecks() {}

    static void run() {
        colorAliases();
        stencilAspect();
        PackedAspectChecks.run();
        int root = glGenTextures(), view = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, root);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, 2, 2);
        glTextureView(view, GL_TEXTURE_2D, root, GL_RGBA8, 0, 1, 0, 1);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    private static void colorAliases() {
        int root = glGenTextures(), view = glGenTextures(), nested = glGenTextures();
        try {
            glBindTexture(GL_TEXTURE_2D, root);
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, 8, 8);
            glTextureView(view, GL_TEXTURE_2D, root, GL_RGBA8, 0, 1, 0, 1);
            glTextureView(nested, GL_TEXTURE_2D, view, GL_RGBA8, 0, 1, 0, 1);
            glBindTexture(GL_TEXTURE_2D, view);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            if (glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_IMMUTABLE_FORMAT) != GL_TRUE
                    || glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_VIEW_NUM_LEVELS) != 1) {
                throw new IllegalStateException("View metadata missing");
            }
            glBindTexture(GL_TEXTURE_2D, root);
            if (glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S) != GL_REPEAT
                    || glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_VIEW_NUM_LEVELS) != 1
                    || glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_VIEW_NUM_LAYERS) != 1) {
                throw new IllegalStateException("View sampler state leaked into parent");
            }
            glClearTexSubImage(root, 0, 0, 0, 0, 8, 8, 1, GL_RGBA, GL_FLOAT, new float[] {1, 0, 0, 1});
            verifyColor(ResourceReadback.layer(view, 0), false);
            glClearTexSubImage(view, 0, 2, 3, 0, 1, 1, 1, GL_RGBA, GL_FLOAT, new float[] {0, 1, 0, 1});
            verifyColor(ResourceReadback.layer(root, 0), true);
            glDeleteTextures(root);
            verifyColor(ResourceReadback.layer(nested, 0), true);
            glDeleteTextures(view);
            verifyColor(ResourceReadback.layer(nested, 0), true);
            int child = glGenTextures();
            glTextureView(child, GL_TEXTURE_2D, nested, GL_RGBA8, 0, 1, 0, 1);
            glDeleteTextures(child);
            verifyColor(ResourceReadback.layer(nested, 0), true);
            glBindTexture(GL_TEXTURE_2D, nested);
            boolean rejected = false;
            try {
                glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, 4, 4);
            } catch (IllegalStateException expected) {
                rejected = true;
            }
            if (!rejected) throw new IllegalStateException("Immutable view storage was redefined");
            verifyColor(ResourceReadback.layer(nested, 0), true);
        } finally {
            glDeleteTextures(root);
            glDeleteTextures(view);
            glDeleteTextures(nested);
        }
    }

    private static void verifyColor(byte[] pixels, boolean green) {
        for (int i = 0; i < 64; i++) {
            boolean changed = green && i == 3 * 8 + 2;
            if ((pixels[i * 4] & 255) != (changed ? 0 : 255)
                    || (pixels[i * 4 + 1] & 255) != (changed ? 255 : 0)
                    || pixels[i * 4 + 2] != 0
                    || (pixels[i * 4 + 3] & 255) != 255) {
                throw new IllegalStateException("Shared view content mismatch at pixel " + i);
            }
        }
    }

    private static void stencilAspect() {
        int root = glGenTextures(), view = glGenTextures(), color = glGenTextures(), fbo = glGenFramebuffers();
        int program = 0;
        try {
            glBindTexture(GL_TEXTURE_2D, root);
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_DEPTH24_STENCIL8, 16, 16);
            glTextureView(view, GL_TEXTURE_2D, root, GL_DEPTH24_STENCIL8, 0, 1, 0, 1);
            glBindTexture(GL_TEXTURE_2D, view);
            glTexParameteri(GL_TEXTURE_2D, GL_DEPTH_STENCIL_TEXTURE_MODE, GL_STENCIL_INDEX);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            if (glGetTexParameteri(GL_TEXTURE_2D, GL_DEPTH_STENCIL_TEXTURE_MODE) != GL_STENCIL_INDEX) {
                throw new IllegalStateException("Stencil aspect selection missing");
            }
            glBindTexture(GL_TEXTURE_2D, root);
            if (glGetTexParameteri(GL_TEXTURE_2D, GL_DEPTH_STENCIL_TEXTURE_MODE) != GL_DEPTH_COMPONENT) {
                throw new IllegalStateException("View aspect state leaked into parent");
            }
            glBindTexture(GL_TEXTURE_2D, color);
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, 16, 16);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_TEXTURE_2D, root, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Stencil view test framebuffer incomplete");
            }
            glViewport(0, 0, 16, 16);
            glStencilMask(255);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_BLEND);
            glDisable(GL_CULL_FACE);
            glEnable(GL_SCISSOR_TEST);
            for (int reference = 0; reference < 256; reference++) {
                glScissor(reference % 16, reference / 16, 1, 1);
                glClearStencil(reference);
                glClear(GL_STENCIL_BUFFER_BIT);
            }
            glDisable(GL_SCISSOR_TEST);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_TEXTURE_2D, 0, 0);
            glBindTexture(GL_TEXTURE_2D, root);
            try (var stack = MemoryStack.stackPush()) {
                var rawStencil = stack.calloc(256);
                glGetTexImage(GL_TEXTURE_2D, 0, GL_STENCIL_INDEX, GL_UNSIGNED_BYTE, rawStencil);
                for (int i = 0; i < 256; i++)
                    if ((rawStencil.get(i) & 255) != i) {
                        throw new IllegalStateException(
                                "Source stencil mismatch at " + i + ": " + (rawStencil.get(i) & 255));
                    }
            }
            program = program();
            glUseProgram(program);
            glUniform1i(glGetUniformLocation(program, "stencilImage"), 0);
            glBindTexture(GL_TEXTURE_2D, view);
            boolean rejectedCompute = false;
            try {
                glDispatchCompute(1, 1, 1);
            } catch (UnsupportedOperationException expected) {
                rejectedCompute = true;
            }
            if (!rejectedCompute) throw new IllegalStateException("Unsupported compute stencil sampling was accepted");
            glDrawArrays(GL_TRIANGLES, 0, 3);
            verifyStencil(color, -1);
            // Writes through a view attached to an FBO must update the same source storage.
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_TEXTURE_2D, view, 0);
            glClearStencil(173);
            glClear(GL_STENCIL_BUFFER_BIT);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_TEXTURE_2D, 0, 0);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            verifyStencil(color, 173);
            glDeleteTextures(root);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            verifyStencil(color, 173);
        } finally {
            glUseProgram(0);
            if (program != 0) glDeleteProgram(program);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(fbo);
            glDeleteTextures(root);
            glDeleteTextures(view);
            glDeleteTextures(color);
            glClearStencil(0);
        }
    }

    private static void verifyStencil(int color, int constant) {
        byte[] pixels = ResourceReadback.layer(color, 0);
        for (int i = 0; i < 256; i++) {
            int expected = constant < 0 ? i : constant;
            if ((pixels[i * 4] & 255) != expected || (pixels[i * 4 + 3] & 255) != 255) {
                throw new IllegalStateException(
                        "Stencil sampling mismatch at pixel " + i + ": " + (pixels[i * 4] & 255) + " != " + expected);
            }
        }
    }

    private static int program() {
        int program = glCreateProgram();
        int vs = shader(GL_VERTEX_SHADER, """
                #version 330 core
                void main() {
                    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
                }
                """);
        int fs = shader(GL_FRAGMENT_SHADER, """
                #version 330 core
                uniform usampler2D stencilImage;
                layout(location = 0) out vec4 color;
                void main() {
                    uint value = texelFetch(stencilImage, ivec2(gl_FragCoord.xy), 0).r;
                    color = vec4(float(value) / 255.0, 0.0, 0.0, 1.0);
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
