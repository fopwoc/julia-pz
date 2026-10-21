package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL14.GL_CONSTANT_ALPHA;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL40.glBlendFuncSeparatei;
import static org.lwjgl.opengl.GL40.glBlendFunci;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.opengl.GL45;

/** Real MRT pixels cover indexed RGB/alpha factors, output routing and cached global-state restoration. */
final class BlendChecks {
    private BlendChecks() {}

    static void run(Path output) throws IOException {
        Files.deleteIfExists(output.resolve("indexed-blend.txt"));
        int first = GL45.glCreateTextures(GL_TEXTURE_2D), second = GL45.glCreateTextures(GL_TEXTURE_2D);
        int fbo = glGenFramebuffers(), program = program();
        try {
            for (int texture : new int[] {first, second}) GL45.glTextureStorage2D(texture, 1, GL_RGBA8, 2, 2);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, first, 0);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, second, 0);
            glDrawBuffers(new int[] {GL_COLOR_ATTACHMENT1, GL_COLOR_ATTACHMENT0});
            glViewport(0, 0, 2, 2);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_SCISSOR_TEST);
            glDisable(GL_CULL_FACE);
            glColorMask(true, true, true, true);
            glClearBufferfv(GL_COLOR, 0, new float[] {0.1f, 0.2f, 0.3f, 0.4f});
            glClearBufferfv(GL_COLOR, 1, new float[] {0.1f, 0.2f, 0.3f, 0.4f});
            glBlendFunc(GL_ONE, GL_ZERO);
            glEnable(GL_BLEND);
            glBlendFunci(0, GL_ONE, GL_ONE);
            glBlendFunci(1, GL_ZERO, GL_SRC_COLOR);
            glUseProgram(program);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            verify(first, 10, 10, 8, 26);
            verify(second, 77, 153, 230, 230);
            glBlendFunci(0, GL_ONE, GL_ZERO);
            try {
                glBlendFunci(8, GL_ONE, GL_ZERO);
                throw new AssertionError("Out-of-range blend output accepted");
            } catch (IllegalArgumentException expected) {
            }
            try {
                glBlendFunci(1, GL_ONE, GL_CONSTANT_ALPHA);
                throw new AssertionError("Unsupported blend factor silently substituted");
            } catch (UnsupportedOperationException expected) {
            }
            glDrawArrays(GL_TRIANGLES, 0, 3);
            verify(first, 4, 2, 1, 6);
            verify(second, 51, 102, 153, 128);
            // This matches the globally cached value from before the indexed writes and must still reach all slots.
            glBlendFunc(GL_ONE, GL_ZERO);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            verify(first, 102, 51, 26, 64);
            verify(second, 51, 102, 153, 128);
            glDisable(GL_BLEND);
            glClearBufferfv(GL_COLOR, 0, new float[] {.1f, .2f, .3f, .4f});
            glClearBufferfv(GL_COLOR, 1, new float[] {.1f, .2f, .3f, .4f});
            glColorMaski(1, false, false, false, false);
            org.lwjgl.opengl.GL30.glEnablei(GL_BLEND, 0);
            glBlendFuncSeparatei(0, GL_ONE, GL_ONE, GL_ZERO, GL_ONE);
            if (!org.lwjgl.opengl.GL30.glIsEnabledi(GL_BLEND, 0) || org.lwjgl.opengl.GL30.glIsEnabledi(GL_BLEND, 1))
                throw new IllegalStateException("Indexed blend enables are not independent");
            var enabled = ByteBuffer.allocateDirect(1);
            org.lwjgl.opengl.GL30.glGetBooleani_v(GL_BLEND, 0, enabled);
            if (enabled.get(0) != GL_TRUE) throw new IllegalStateException("Indexed boolean enable query differs");
            org.lwjgl.opengl.GL30.glGetBooleani_v(GL_BLEND, 1, enabled);
            if (enabled.get(0) != GL_FALSE) throw new IllegalStateException("Indexed boolean disable query differs");
            glDrawArrays(GL_TRIANGLES, 0, 3);
            verify(first, 26, 51, 77, 102);
            verify(second, 77, 153, 230, 102);
            org.lwjgl.opengl.GL30.glDisablei(GL_BLEND, 0);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            verify(first, 26, 51, 77, 102);
            verify(second, 51, 102, 153, 128);
            glColorMask(true, true, true, true);
            glEnable(GL_BLEND);
            glBlendFunc(GL_ONE, GL_ONE);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            verify(first, 128, 102, 102, 166);
            verify(second, 102, 204, 255, 255);
            try {
                org.lwjgl.opengl.GL30.glEnablei(GL_BLEND, 8);
                throw new AssertionError("Out-of-range blend enable accepted");
            } catch (IllegalArgumentException expected) {
            }
            try {
                org.lwjgl.opengl.GL30.glDisablei(GL_DEPTH_TEST, 0);
                throw new AssertionError("Non-indexed capability silently changed global state");
            } catch (UnsupportedOperationException expected) {
            }
            Files.writeString(
                    output.resolve("indexed-blend.txt"),
                    "PASS\nverified_pixels=48\nchecks=indexed factors,MRT routing,RGB and alpha,isolated updates,global cache invalidation,atomic invalid arguments,indexed enable/disable/query,separate RGB/alpha factors,color masks,global enable restoration\n");
        } finally {
            glUseProgram(0);
            glColorMask(true, true, true, true);
            glBlendFunc(GL_ONE, GL_ZERO);
            glDisable(GL_BLEND);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(fbo);
            glDeleteTextures(first);
            glDeleteTextures(second);
            glDeleteProgram(program);
        }
    }

    private static void verify(int texture, int red, int green, int blue, int alpha) {
        byte[] pixels = ResourceReadback.layer(texture, 0);
        int[] expected = {red, green, blue, alpha};
        for (int i = 0; i < pixels.length; i++)
            if (Math.abs(Byte.toUnsignedInt(pixels[i]) - expected[i % 4]) > 1)
                throw new IllegalStateException("Indexed blend pixel mismatch: byte=" + i + " actual="
                        + Byte.toUnsignedInt(pixels[i]) + " expected=" + expected[i % 4]);
    }

    private static int program() {
        int program = glCreateProgram();
        String vertex =
                "#version 330 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0,1);}";
        String fragment =
                "#version 330 core\nlayout(location=0)out vec4 first;layout(location=1)out vec4 second;void main(){first=vec4(.2,.4,.6,.5);second=vec4(.4,.2,.1,.25);}";
        for (int type : new int[] {GL_VERTEX_SHADER, GL_FRAGMENT_SHADER}) {
            int shader = glCreateShader(type);
            glShaderSource(shader, type == GL_VERTEX_SHADER ? vertex : fragment);
            glCompileShader(shader);
            if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
                throw new IllegalStateException(glGetShaderInfoLog(shader));
            glAttachShader(program, shader);
            glDeleteShader(shader);
        }
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
            throw new IllegalStateException(glGetProgramInfoLog(program));
        return program;
    }
}
