package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.IntSupplier;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Actual Metal pixels verify named FBO isolation, MRT routing, slice clears/draws, depth slices and mip targets. */
final class FramebufferChecks {
    private static int verified;

    private FramebufferChecks() {}

    static void run(Path output) throws IOException {
        verified = 0;
        Files.deleteIfExists(output.resolve("framebuffers.txt"));
        int[] fbos = new int[5];
        GL45.glCreateFramebuffers(fbos);
        IntSupplier create = GL45::glCreateFramebuffers;
        int peer = create.getAsInt();
        try (var stack = MemoryStack.stackPush()) {
            var names = stack.ints(-1, -1, -1);
            names.position(1).limit(2);
            GL45.glCreateFramebuffers(names);
            if (names.position() != 1 || names.get(0) != -1 || !glIsFramebuffer(names.get(1)))
                throw new IllegalStateException("Framebuffer output buffer position/canary corrupted");
            glDeleteFramebuffers(names.get(1));
        }
        int first = GL45.glCreateTextures(GL_TEXTURE_2D), second = GL45.glCreateTextures(GL_TEXTURE_2D);
        int color = GL45.glCreateTextures(GL_TEXTURE_2D_ARRAY), depth = GL45.glCreateTextures(GL_TEXTURE_2D_ARRAY);
        int blank = GL45.glCreateTextures(GL_TEXTURE_2D);
        int paint = program(0), sample = program(1), depthPaint = program(2);
        var bytes = MemoryUtil.memAlloc(4 * 4 * 3 * 4);
        try {
            glViewport(0, 0, 4, 4);
            glDisable(GL_BLEND);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_SCISSOR_TEST);
            glDisable(GL_CULL_FACE);
            glColorMask(true, true, true, true);
            glDepthMask(true);
            GL45.glTextureStorage2D(first, 1, GL_RGBA8, 4, 4);
            GL45.glTextureStorage2D(second, 1, GL_RGBA8, 4, 4);
            GL45.glTextureStorage3D(color, 2, GL_RGBA8, 4, 4, 3);
            GL45.glTextureStorage3D(depth, 1, GL_DEPTH_COMPONENT32F, 4, 4, 3);
            GL45.glTextureParameteri(depth, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            GL45.glTextureParameteri(depth, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            for (int i = 0; i < bytes.capacity(); i += 4) {
                bytes.put(i, (byte) 51);
                bytes.put(i + 1, (byte) 102);
                bytes.put(i + 2, (byte) 153);
                bytes.put(i + 3, (byte) 255);
            }
            GL45.glTextureSubImage3D(color, 0, 0, 0, 0, 4, 4, 3, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
            GL45.glTextureSubImage3D(color, 1, 0, 0, 0, 2, 2, 3, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
            for (int fbo : fbos)
                if (!glIsFramebuffer(fbo)) throw new IllegalStateException("Created FBO does not exist before binding");
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbos[0]);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, fbos[1]);
            if (GL45.glCheckNamedFramebufferStatus(fbos[4], GL_FRAMEBUFFER)
                    != GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT)
                throw new IllegalStateException("Empty FBO reported complete");
            GL45.glNamedFramebufferTexture(fbos[4], GL_COLOR_ATTACHMENT0, blank, 0);
            if (GL45.glCheckNamedFramebufferStatus(fbos[4], GL_FRAMEBUFFER) != GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT)
                throw new IllegalStateException("Unallocated attachment reported complete");
            GL45.glNamedFramebufferTexture(fbos[4], GL_COLOR_ATTACHMENT0, 0, 0);
            GL45.glNamedFramebufferTexture(fbos[2], GL_COLOR_ATTACHMENT0, first, 0);
            GL45.glNamedFramebufferTexture(fbos[2], GL_COLOR_ATTACHMENT1, second, 0);
            int[] routing = {GL_COLOR_ATTACHMENT1, GL_COLOR_ATTACHMENT0};
            GL45.glNamedFramebufferDrawBuffers(fbos[2], routing);
            GL45.glNamedFramebufferReadBuffer(fbos[2], GL_COLOR_ATTACHMENT1);
            if (routing[0] != GL_COLOR_ATTACHMENT1 || routing[1] != GL_COLOR_ATTACHMENT0)
                throw new IllegalStateException("Named routing consumed its array");
            unchanged(fbos[0], fbos[1]);
            complete(fbos[2]);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbos[2]);
            glUseProgram(paint);
            glUniform4f(glGetUniformLocation(paint, "shade"), 0.2f, 0.4f, 0.6f, 1);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            verify(ResourceReadback.layer(first, 0), 204, 51, 102);
            verify(ResourceReadback.layer(second, 0), 51, 102, 153);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, fbos[2]);
            bytes.clear();
            glReadPixels(0, 0, 4, 4, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
            var read = new byte[64];
            bytes.get(read);
            verify(read, 51, 102, 153);
            GL45.glNamedFramebufferReadBuffer(fbos[2], GL_NONE);
            try {
                glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
                throw new IllegalStateException("Read from GL_NONE accepted");
            } catch (IllegalStateException expected) {
                if (!expected.getMessage().contains("GL_NONE")) throw expected;
            }
            GL45.glNamedFramebufferReadBuffer(fbos[2], GL_COLOR_ATTACHMENT1);
            // Select one slice, clear it, then draw a different slice without disturbing its neighbors.
            GL45.glNamedFramebufferTextureLayer(fbos[3], GL_COLOR_ATTACHMENT0, color, 0, 1);
            GL45.glNamedFramebufferDrawBuffer(fbos[3], GL_COLOR_ATTACHMENT0);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbos[3]);
            glClearColor(0.8f, 0.2f, 0.4f, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            verify(ResourceReadback.layer(color, 0), 51, 102, 153);
            verify(ResourceReadback.layer(color, 1), 204, 51, 102);
            verify(ResourceReadback.layer(color, 2), 51, 102, 153);
            GL45.glNamedFramebufferTextureLayer(fbos[3], GL_COLOR_ATTACHMENT0, color, 0, 2);
            glUniform4f(glGetUniformLocation(paint, "shade"), 0.4f, 0.6f, 0.8f, 1);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            glEnable(GL_SCISSOR_TEST);
            glScissor(1, 1, 2, 2);
            glColorMask(true, false, false, false);
            glClearColor(1, 0, 0, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            glColorMask(true, true, true, true);
            glDisable(GL_SCISSOR_TEST);
            read = ResourceReadback.layer(color, 2);
            for (int y = 0; y < 4; y++)
                for (int x = 0; x < 4; x++)
                    pixel(read, (y * 4 + x) * 4, x >= 1 && x < 3 && y >= 1 && y < 3 ? 255 : 102, 153, 204);
            // A read-target attachment edit must not edit the separately bound draw target.
            glBindFramebuffer(GL_READ_FRAMEBUFFER, fbos[4]);
            glFramebufferTextureLayer(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, color, 0, 0);
            unchanged(fbos[3], fbos[4]);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbos[4]);
            glUniform4f(glGetUniformLocation(paint, "shade"), 0.6f, 0.2f, 0.8f, 1);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            verify(ResourceReadback.layer(color, 0), 153, 51, 204);
            verify(ResourceReadback.layer(color, 1), 204, 51, 102);
            // Mip/layer selection participates in the render target, not only attachment metadata.
            GL45.glNamedFramebufferTextureLayer(fbos[3], GL_COLOR_ATTACHMENT0, color, 1, 1);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbos[3]);
            glViewport(0, 0, 2, 2);
            glUniform4f(glGetUniformLocation(paint, "shade"), 0.8f, 0.4f, 0.2f, 1);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbos[2]);
            GL45.glNamedFramebufferDrawBuffer(fbos[2], GL_COLOR_ATTACHMENT0);
            glViewport(0, 0, 4, 4);
            glUseProgram(sample);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D_ARRAY, color);
            glUniform1i(glGetUniformLocation(sample, "source"), 0);
            glUniform1i(glGetUniformLocation(sample, "mip"), 1);
            for (int layer = 0; layer < 3; layer++) {
                glUniform1i(glGetUniformLocation(sample, "layer"), layer);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                verify(ResourceReadback.layer(first, 0), layer == 1 ? 204 : 51, 102, layer == 1 ? 51 : 153);
            }
            // Two depth-only FBOs use the same texture with different slices; binding must break the old pass.
            GL45.glNamedFramebufferTextureLayer(fbos[0], GL_DEPTH_ATTACHMENT, depth, 0, 0);
            GL45.glNamedFramebufferDrawBuffer(fbos[0], GL_NONE);
            GL45.glNamedFramebufferReadBuffer(fbos[0], GL_NONE);
            GL45.glNamedFramebufferTextureLayer(peer, GL_DEPTH_ATTACHMENT, depth, 0, 1);
            GL45.glNamedFramebufferDrawBuffer(peer, GL_NONE);
            GL45.glNamedFramebufferReadBuffer(peer, GL_NONE);
            complete(fbos[0]);
            complete(peer);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbos[0]);
            glClearDepth(0.25);
            glClear(GL_DEPTH_BUFFER_BIT);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, peer);
            glClearDepth(0.5);
            glClear(GL_DEPTH_BUFFER_BIT);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbos[0]);
            glClearDepth(0.75);
            glClear(GL_DEPTH_BUFFER_BIT);
            GL45.glNamedFramebufferTextureLayer(peer, GL_DEPTH_ATTACHMENT, depth, 0, 2);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, peer);
            glClearDepth(1);
            glClear(GL_DEPTH_BUFFER_BIT);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbos[0]);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LESS);
            glUseProgram(depthPaint);
            glUniform1f(glGetUniformLocation(depthPaint, "clipZ"), -0.5f);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            glUniform1f(glGetUniformLocation(depthPaint, "clipZ"), 0.5f);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            glDisable(GL_DEPTH_TEST);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbos[2]);
            glUseProgram(sample);
            glBindTexture(GL_TEXTURE_2D_ARRAY, depth);
            glUniform1i(glGetUniformLocation(sample, "grayscale"), 1);
            glUniform1i(glGetUniformLocation(sample, "mip"), 0);
            for (int layer = 0; layer < 3; layer++) {
                glUniform1i(glGetUniformLocation(sample, "layer"), layer);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                int expected = layer == 0 ? 64 : layer == 1 ? 128 : 255;
                verify(ResourceReadback.layer(first, 0), expected, expected, expected);
            }
            // Unsupported reads from a slice must fail instead of reading layer zero.
            glBindFramebuffer(GL_READ_FRAMEBUFFER, fbos[4]);
            try {
                glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
                throw new IllegalStateException("Slice readback silently accepted");
            } catch (UnsupportedOperationException expected) {
            }
            int draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING), reader = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
            try {
                GL45.glNamedFramebufferTextureLayer(fbos[3], GL_COLOR_ATTACHMENT0, color, 0, 3);
                throw new IllegalStateException("Out-of-range slice accepted");
            } catch (IllegalArgumentException expected) {
                unchanged(draw, reader);
            }
            Files.writeString(
                    output.resolve("framebuffers.txt"),
                    "PASS\nverified_pixels=" + verified
                            + "\nchecks=named creation,bindings,MRT routing,read routing,completeness,array slices,scissor/masks,mips,depth-only clears,slice switching,read-target attachment,invalid state\n");
        } finally {
            glUseProgram(0);
            glClearDepth(1);
            glColorMask(true, true, true, true);
            glDisable(GL_SCISSOR_TEST);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
            glDeleteFramebuffers(fbos);
            glDeleteFramebuffers(peer);
            for (int texture : new int[] {first, second, color, depth, blank}) glDeleteTextures(texture);
            glDeleteProgram(paint);
            glDeleteProgram(sample);
            glDeleteProgram(depthPaint);
            MemoryUtil.memFree(bytes);
        }
    }

    private static void complete(int name) {
        if (GL45.glCheckNamedFramebufferStatus(name, GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("Named FBO not complete");
    }

    private static void unchanged(int draw, int read) {
        if (glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING) != draw || glGetInteger(GL_READ_FRAMEBUFFER_BINDING) != read)
            throw new IllegalStateException("Named operation changed framebuffer bindings");
    }

    private static int program(int kind) {
        int vertex = glCreateShader(GL_VERTEX_SHADER),
                fragment = glCreateShader(GL_FRAGMENT_SHADER),
                program = glCreateProgram();
        glShaderSource(
                vertex,
                "#version 330 core\nuniform float clipZ;void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,clipZ,1);}");
        glShaderSource(
                fragment,
                kind == 2
                        ? "#version 330 core\nvoid main(){}"
                        : kind == 1
                                ? "#version 330 core\nuniform sampler2DArray source;uniform int mip;uniform int layer;uniform int grayscale;layout(location=0)out vec4 color;void main(){vec4 value=texelFetch(source,ivec3(0,0,layer),mip);color=grayscale!=0?vec4(vec3(value.r),1):value;}"
                                : "#version 330 core\nuniform vec4 shade;layout(location=0)out vec4 first;layout(location=1)out vec4 second;void main(){first=shade;second=vec4(0.8,0.2,0.4,1);}");
        for (int shader : new int[] {vertex, fragment}) {
            glCompileShader(shader);
            if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
                throw new IllegalStateException(glGetShaderInfoLog(shader));
            glAttachShader(program, shader);
            glDeleteShader(shader);
        }
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
            throw new IllegalStateException(glGetProgramInfoLog(program));
        glUseProgram(0);
        return program;
    }

    private static void verify(byte[] pixels, int r, int g, int b) {
        for (int i = 0; i < 16; i++) pixel(pixels, i * 4, r, g, b);
    }

    private static void pixel(byte[] pixels, int offset, int r, int g, int b) {
        int[] expected = {r, g, b, 255};
        for (int c = 0; c < 4; c++)
            if (Math.abs(Byte.toUnsignedInt(pixels[offset + c]) - expected[c]) > 1)
                throw new IllegalStateException("Framebuffer pixel=" + offset / 4 + " channel=" + c + " expected="
                        + expected[c] + " actual=" + Byte.toUnsignedInt(pixels[offset + c]));
        verified++;
    }
}
