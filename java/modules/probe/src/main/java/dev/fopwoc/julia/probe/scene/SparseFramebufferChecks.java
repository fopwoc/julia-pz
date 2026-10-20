package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

import dev.fopwoc.julia.backend.Upstream;
import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryStack;

/** Sparse output slots must discard independently, without compacting outputs or clipping targets to a dummy image. */
final class SparseFramebufferChecks {
    private static final int WIDTH = 8, HEIGHT = 6;
    private static final int[] RED = {255, 0, 0, 255},
            GREEN = {0, 255, 0, 255},
            BLUE = {0, 0, 255, 255},
            YELLOW = {255, 255, 0, 255},
            INITIAL = {17, 34, 51, 255};
    private static final String VERTEX =
            "#version 430 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0,1);}";

    private SparseFramebufferChecks() {}

    static void run(Path output) throws IOException {
        Files.deleteIfExists(output.resolve("sparse-framebuffers.txt"));
        int fbo = glGenFramebuffers(), other = glGenFramebuffers();
        int[] textures = {glGenTextures(), glGenTextures(), glGenTextures(), glGenTextures()};
        int array = glGenTextures(), depth = glGenTextures(), integer = glGenTextures();
        int paint = program("""
                #version 430 core
                uniform float gain;
                layout(location=0)out vec4 first;layout(location=1)out vec4 second;
                layout(location=2)out vec4 third;layout(location=3)out vec4 fourth;
                void main(){first=vec4(gain,0,0,1);second=vec4(0,gain,0,1);third=vec4(0,0,gain,1);fourth=vec4(gain,gain,0,1);}
                """);
        int integerPaint = program("""
                #version 430 core
                layout(location=0)out vec4 ignored;layout(location=2)out uint value;
                void main(){ignored=vec4(1);value=427u;}
                """);
        int verified = 0;
        try (var stack = MemoryStack.stackPush()) {
            var data = stack.malloc(WIDTH * HEIGHT * 2 * 4);
            for (int i = 0; i < data.capacity(); i += 4) for (int c = 0; c < 4; c++) data.put(i + c, (byte) INITIAL[c]);
            glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
            for (int texture : textures) {
                glBindTexture(GL_TEXTURE_2D, texture);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, WIDTH, HEIGHT, 0, GL_RGBA, GL_UNSIGNED_BYTE, data);
            }
            glBindTexture(GL_TEXTURE_2D_ARRAY, array);
            GL12.glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, WIDTH, HEIGHT, 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, data);
            glBindTexture(GL_TEXTURE_2D, depth);
            glTexImage2D(
                    GL_TEXTURE_2D,
                    0,
                    GL_DEPTH_COMPONENT32F,
                    WIDTH,
                    HEIGHT,
                    0,
                    GL_DEPTH_COMPONENT,
                    GL_FLOAT,
                    (ByteBuffer) null);
            glBindTexture(GL_TEXTURE_2D, integer);
            glTexImage2D(
                    GL_TEXTURE_2D, 0, GL_R32UI, WIDTH, HEIGHT, 0, GL_RED_INTEGER, GL_UNSIGNED_INT, (ByteBuffer) null);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            for (int i = 0; i < 4; i++)
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + i, GL_TEXTURE_2D, textures[i], 0);
            glDisable(GL_BLEND);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_SCISSOR_TEST);
            glDisable(GL_CULL_FACE);
            glColorMask(true, true, true, true);
            glDepthMask(true);
            glViewport(0, 0, WIDTH, HEIGHT);
            glUseProgram(paint);
            int gain = glGetUniformLocation(paint, "gain");
            glUniform1f(gain, 1);
            var routes =
                    stack.ints(-99, GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1, GL_COLOR_ATTACHMENT2, GL_NONE, -98);
            routes.position(1).limit(5);
            glDrawBuffers(routes);
            if (routes.position() != 1 || routes.limit() != 5 || routes.get(0) != -99)
                throw new IllegalStateException("Sparse routing consumed client buffer");
            glDrawArrays(GL_TRIANGLES, 0, 3);
            verified += verify(textures[0], RED)
                    + verify(textures[1], GREEN)
                    + verify(textures[2], BLUE)
                    + verify(textures[3], INITIAL);
            GL45.glNamedFramebufferDrawBuffers(
                    fbo, new int[] {GL_NONE, GL_COLOR_ATTACHMENT3, GL_NONE, GL_COLOR_ATTACHMENT1});
            glDrawArrays(GL_TRIANGLES, 0, 3);
            verified += verify(textures[0], RED)
                    + verify(textures[1], YELLOW)
                    + verify(textures[2], BLUE)
                    + verify(textures[3], GREEN);
            try {
                GL45.glNamedFramebufferDrawBuffers(
                        fbo, new int[] {GL_COLOR_ATTACHMENT0, GL_NONE, GL_COLOR_ATTACHMENT0});
                throw new IllegalStateException("Duplicate sparse draw attachment accepted");
            } catch (IllegalArgumentException accepted) {
            }
            // Leading holes must keep the same open render pass across consecutive draws.
            long before = Upstream.frames().frame().renderPassGeneration;
            glEnable(GL_SCISSOR_TEST);
            glUniform1f(gain, 0.5f);
            glScissor(0, 0, 4, HEIGHT);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            long opened = Upstream.frames().frame().renderPassGeneration;
            glUniform1f(gain, 0.25f);
            glScissor(4, 0, 4, HEIGHT);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            if (opened <= before || Upstream.frames().frame().renderPassGeneration != opened)
                throw new IllegalStateException("Leading sparse slot prevented open-pass reuse");
            glDisable(GL_SCISSOR_TEST);
            verified += verifySplit(textures[1], new int[] {128, 128, 0, 255}, new int[] {64, 64, 0, 255});
            verified += verifySplit(textures[3], new int[] {0, 128, 0, 255}, new int[] {0, 64, 0, 255});
            // Clear affects selected physical attachments only; inactive attachment zero stays red.
            glClearColor(0.2f, 0.4f, 0.6f, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            glDrawArrays(GL_TRIANGLES, 0, 0);
            Upstream.clears().materializeAllPendingClears(Upstream.context());
            int[] clear = {51, 102, 153, 255};
            verified += verify(textures[0], RED)
                    + verify(textures[1], clear)
                    + verify(textures[2], BLUE)
                    + verify(textures[3], clear);
            glColorMask(false, true, false, true);
            glClearColor(0, 1, 0, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            glColorMask(true, true, true, true);
            clear[1] = 255;
            verified += verify(textures[0], RED)
                    + verify(textures[1], clear)
                    + verify(textures[2], BLUE)
                    + verify(textures[3], clear);
            // A second framebuffer with an array slice keeps inactive neighboring layers intact.
            glBindFramebuffer(GL_FRAMEBUFFER, other);
            glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, array, 0, 1);
            glDrawBuffers(new int[] {GL_NONE, GL_NONE, GL_COLOR_ATTACHMENT1});
            glUniform1f(gain, 1);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            verified += verifyLayer(array, 0, INITIAL) + verifyLayer(array, 1, BLUE);
            // Sparse pipeline caches must not retain stale holes after routing is changed to dense.
            glDrawBuffer(GL_COLOR_ATTACHMENT1);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            verified += verifyLayer(array, 1, RED);
            // An unsigned output with a disabled floating output requires no fabricated attachment format.
            glBindFramebuffer(GL_FRAMEBUFFER, other);
            glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, 0, 0, 0);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT3, GL_TEXTURE_2D, integer, 0);
            glDrawBuffers(new int[] {GL_NONE, GL_NONE, GL_COLOR_ATTACHMENT3});
            glUseProgram(integerPaint);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            var values = ByteBuffer.wrap(ResourceReadback.layer(integer, 0))
                    .order(ByteOrder.nativeOrder())
                    .asIntBuffer();
            for (int i = 0; i < values.remaining(); i++)
                if (values.get(i) != 427) throw new IllegalStateException("Sparse integer output mapping failed");
            verified += WIDTH * HEIGHT;
            // All GL_NONE buffers still permit depth rendering and must leave every color attachment unchanged.
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glUseProgram(paint);
            glUniform1f(gain, 1);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depth, 0);
            glDrawBuffers(new int[] {GL_NONE, GL_NONE, GL_NONE, GL_NONE});
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LESS);
            glClearDepth(1);
            glClear(GL_DEPTH_BUFFER_BIT);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            glDrawBuffer(GL_COLOR_ATTACHMENT0);
            glUniform1f(gain, 0.25f);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            glDisable(GL_DEPTH_TEST);
            verified += verify(textures[0], RED)
                    + verify(textures[1], clear)
                    + verify(textures[2], BLUE)
                    + verify(textures[3], clear);
            Files.writeString(
                    output.resolve("sparse-framebuffers.txt"),
                    "PASS\nverified_pixels=" + verified
                            + "\nchecks=trailing/interior/leading GL_NONE,nonconsecutive routing,disabled attachment preservation,full render area,buffer positions,duplicate rejection,open-pass reuse,scissor,selected and masked clears,array slices,route changes,unsigned fragment outputs,all-NONE depth rendering\n");
        } finally {
            glUseProgram(0);
            glDisable(GL_SCISSOR_TEST);
            glDisable(GL_DEPTH_TEST);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(fbo);
            glDeleteFramebuffers(other);
            glDeleteTextures(textures);
            glDeleteTextures(array);
            glDeleteTextures(depth);
            glDeleteTextures(integer);
            glDeleteProgram(paint);
            glDeleteProgram(integerPaint);
        }
    }

    private static int verify(int texture, int[] color) {
        return verifyLayer(texture, 0, color);
    }

    private static int verifyLayer(int texture, int layer, int[] color) {
        var pixels = ResourceReadback.layer(texture, layer);
        for (int i = 0; i < WIDTH * HEIGHT; i++)
            for (int c = 0; c < 4; c++)
                if (Math.abs((pixels[i * 4 + c] & 255) - color[c]) > 1)
                    throw new IllegalStateException(
                            "Sparse target pixel mismatch: texture=" + texture + " layer=" + layer + " pixel=" + i
                                    + " channel=" + c + " got=" + (pixels[i * 4 + c] & 255) + " expected=" + color[c]);
        return WIDTH * HEIGHT;
    }

    private static int verifySplit(int texture, int[] left, int[] right) {
        var pixels = ResourceReadback.layer(texture, 0);
        for (int y = 0; y < HEIGHT; y++)
            for (int x = 0; x < WIDTH; x++)
                for (int c = 0; c < 4; c++) {
                    int[] color = x < 4 ? left : right;
                    if (Math.abs((pixels[(y * WIDTH + x) * 4 + c] & 255) - color[c]) > 1)
                        throw new IllegalStateException("Sparse scissor/render extent mismatch");
                }
        return WIDTH * HEIGHT;
    }

    private static int program(String fragment) {
        int vs = glCreateShader(GL_VERTEX_SHADER), fs = glCreateShader(GL_FRAGMENT_SHADER), program = glCreateProgram();
        try {
            glShaderSource(vs, VERTEX);
            glShaderSource(fs, fragment);
            for (int shader : new int[] {vs, fs}) {
                glCompileShader(shader);
                if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
                    throw new IllegalStateException(glGetShaderInfoLog(shader));
                glAttachShader(program, shader);
            }
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
                throw new IllegalStateException(glGetProgramInfoLog(program));
            return program;
        } finally {
            glDeleteShader(vs);
            glDeleteShader(fs);
        }
    }
}
