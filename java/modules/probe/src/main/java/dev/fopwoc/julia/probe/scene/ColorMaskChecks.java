package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

import dev.fopwoc.julia.backend.Upstream;
import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.system.MemoryStack;

/** Indexed color masks affect output slots through MRT routing, clears, and cached global restoration. */
final class ColorMaskChecks {
    private ColorMaskChecks() {}

    static void run(Path output) throws IOException {
        int first = glGenTextures(), second = glGenTextures(), fbo = glGenFramebuffers(), program = program();
        int[] initial = {26, 51, 77, 102}, cleared = {153, 179, 204, 230};
        int pixels = 0;
        try (var stack = MemoryStack.stackPush()) {
            for (int texture : new int[] {first, second}) {
                glBindTexture(GL_TEXTURE_2D, texture);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 2, 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
            }
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, first, 0);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, second, 0);
            glDrawBuffers(new int[] {GL_COLOR_ATTACHMENT1, GL_COLOR_ATTACHMENT0});
            glViewport(0, 0, 2, 2);
            glDisable(GL_BLEND);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_CULL_FACE);
            glDisable(GL_SCISSOR_TEST);
            glUseProgram(program);
            for (int step = 0; step < 32; step++) {
                int mask = step < 16 ? step : 31 - step, inverse = mask ^ 15;
                glColorMask(true, true, true, true);
                glClearColor(.1f, .2f, .3f, .4f);
                glClear(GL_COLOR_BUFFER_BIT);
                set(0, mask);
                set(1, inverse);
                var query = stack.bytes((byte) 7, (byte) 7, (byte) 7, (byte) 7, (byte) 7, (byte) 7)
                        .position(1)
                        .limit(5);
                glGetBooleani_v(GL_COLOR_WRITEMASK, 1, query);
                for (int c = 0; c < 4; c++)
                    if (query.get(c + 1) != ((inverse >> c) & 1))
                        throw new IllegalStateException("Indexed color-mask query differs");
                if (query.position() != 1
                        || query.limit() != 5
                        || query.get(0) != 7
                        || query.duplicate().clear().get(5) != 7)
                    throw new IllegalStateException("Color-mask query changed destination state");
                glDrawArrays(GL_TRIANGLES, 0, 3);
                verify(first, inverse, new int[] {255, 255, 255, 255}, initial);
                verify(second, mask, new int[] {255, 255, 255, 255}, initial);
                glClearColor(.6f, .7f, .8f, .9f);
                glClear(GL_COLOR_BUFFER_BIT);
                verify(first, inverse, cleared, initial);
                verify(second, mask, cleared, initial);
                glClearBufferfv(GL_COLOR, 1, new float[] {0, 0, 0, 0});
                verify(first, inverse, new int[4], initial);
                verify(second, mask, cleared, initial);
                // GLSM's globally cached value may already be all true; indexed overrides still need resetting.
                glColorMask(true, true, true, true);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                verify(first, 15, new int[] {255, 255, 255, 255}, initial);
                verify(second, 15, new int[] {255, 255, 255, 255}, initial);
                pixels += 32;
            }
            try {
                glColorMaski(8, false, false, false, false);
                throw new AssertionError("Out-of-range color mask accepted");
            } catch (IllegalArgumentException expected) {
            }
            glColorMaski(0, false, false, false, false);
            glColorMaski(1, false, true, false, false);
            glEnable(GL_SCISSOR_TEST);
            glScissor(1, 0, 1, 1);
            glClearColor(0, 0, 0, 0);
            glClear(GL_COLOR_BUFFER_BIT);
            byte[] data = ResourceReadback.layer(first, 0);
            for (int pixel = 0; pixel < 4; pixel++)
                for (int c = 0; c < 4; c++)
                    if ((data[pixel * 4 + c] & 255) != (pixel == 1 && c == 1 ? 0 : 255))
                        throw new IllegalStateException("Scissored indexed clear changed another pixel/channel");
            verify(second, 15, new int[] {255, 255, 255, 255}, initial);
            pixels += 8;
            Files.writeString(
                    output.resolve("color-masks.txt"),
                    "PASS\nverified_pixels=" + pixels
                            + "\nchecks=all channel masks,MRT routing,pipeline reuse,global restore,indexed queries,masked clear,per-output clear,scissor,invalid index\n");
        } finally {
            glDisable(GL_SCISSOR_TEST);
            glColorMask(true, true, true, true);
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteTextures(first);
            glDeleteTextures(second);
            glDeleteFramebuffers(fbo);
            glDeleteProgram(program);
        }
    }

    private static void set(int output, int mask) {
        glColorMaski(output, (mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0, (mask & 8) != 0);
    }

    private static void verify(int texture, int mask, int[] written, int[] kept) {
        Upstream.clears()
                .materializePendingClearForTexture(
                        Upstream.context(), Upstream.resources().getTextureHandle(texture));
        byte[] data = ResourceReadback.layer(texture, 0);
        for (int i = 0; i < data.length; i++) {
            int c = i % 4, expected = (mask & (1 << c)) != 0 ? written[c] : kept[c];
            if (Math.abs((data[i] & 255) - expected) > 1)
                throw new IllegalStateException("Color mask mismatch: mask=" + mask + " byte=" + i + " got="
                        + (data[i] & 255) + " expected=" + expected);
        }
    }

    private static int program() {
        int program = glCreateProgram();
        for (int type : new int[] {GL_VERTEX_SHADER, GL_FRAGMENT_SHADER}) {
            int shader = glCreateShader(type);
            glShaderSource(
                    shader,
                    type == GL_VERTEX_SHADER
                            ? "#version 330 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0,1);}"
                            : "#version 330 core\nlayout(location=0)out vec4 first;layout(location=1)out vec4 second;void main(){first=vec4(1);second=vec4(1);}");
            glCompileShader(shader);
            if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0)
                throw new IllegalStateException(glGetShaderInfoLog(shader));
            glAttachShader(program, shader);
            glDeleteShader(shader);
        }
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == 0) throw new IllegalStateException(glGetProgramInfoLog(program));
        return program;
    }
}
