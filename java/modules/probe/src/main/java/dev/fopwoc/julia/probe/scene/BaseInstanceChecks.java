package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.glDrawArraysInstanced;
import static org.lwjgl.opengl.GL42.glDrawArraysInstancedBaseInstance;
import static org.lwjgl.opengl.GL43.*;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryStack;

/** Fetches distinguish base-after-divisor semantics, zero-based instance IDs, first vertices, and VAO restoration. */
final class BaseInstanceChecks {
    private BaseInstanceChecks() {}

    static void run(Path output) throws IOException {
        Files.deleteIfExists(output.resolve("base-instance.txt"));
        int vao = glGenVertexArrays(), colors = glGenBuffers(), alpha = glGenBuffers(), blue = glGenBuffers();
        int texture = glGenTextures(), fbo = glGenFramebuffers(), program = program();
        try (var stack = MemoryStack.stackPush()) {
            glBindVertexArray(vao);
            var values = stack.mallocFloat(2 + 12 * 2);
            values.put(0).put(0);
            for (int i = 0; i < 12; i++) values.put(red(i) / 255f).put(green(i) / 255f);
            values.flip();
            glBindBuffer(GL_ARRAY_BUFFER, colors);
            glBufferData(GL_ARRAY_BUFFER, values, GL_STATIC_DRAW);
            glBindVertexBuffer(6, colors, 8, 8);
            glVertexAttribFormat(2, 1, GL_FLOAT, false, 0);
            glVertexAttribBinding(2, 6);
            glVertexAttribFormat(3, 1, GL_FLOAT, false, 4);
            glVertexAttribBinding(3, 6);
            var alphas = stack.mallocFloat(13);
            alphas.put(0);
            for (int i = 0; i < 12; i++) alphas.put(opacity(i) / 255f);
            alphas.flip();
            glBindBuffer(GL_ARRAY_BUFFER, alpha);
            glBufferData(GL_ARRAY_BUFFER, alphas, GL_STATIC_DRAW);
            glBindVertexBuffer(7, alpha, 4, 4);
            glVertexAttribFormat(4, 1, GL_FLOAT, false, 0);
            glVertexAttribBinding(4, 7);
            glBindBuffer(GL_ARRAY_BUFFER, blue);
            glBufferData(GL_ARRAY_BUFFER, stack.floats(0.2f, 0.2f, 0.2f, 0.2f, 0.8f, 0.8f, 0.8f, 0.8f), GL_STATIC_DRAW);
            glBindVertexBuffer(1, blue, 0, 4);
            glVertexAttribFormat(1, 1, GL_FLOAT, false, 0);
            glVertexAttribBinding(1, 1);
            for (int attribute : new int[] {1, 2, 3, 4}) glEnableVertexAttribArray(attribute);
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 24, 4, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
            glViewport(0, 0, 24, 4);
            glDisable(GL_BLEND);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_SCISSOR_TEST);
            glDisable(GL_CULL_FACE);
            glUseProgram(program);
            for (int[] draw : new int[][] {{0, 1, 1, 0}, {2, 1, 1, 4}, {1, 2, 3, 4}, {3, 3, 2, 0}, {0, 2, 3, 4}}) {
                glVertexBindingDivisor(6, draw[1]);
                glVertexBindingDivisor(7, draw[2]);
                glDrawArraysInstancedBaseInstance(GL_TRIANGLE_STRIP, draw[3], 4, 6, draw[0]);
                verify(texture, draw[0], draw[1], draw[2], draw[3]);
            }
            try {
                glDrawArraysInstancedBaseInstance(GL_TRIANGLE_STRIP, 4, 4, 6, -1);
                throw new IllegalStateException("Unsigned base instance was truncated or wrapped");
            } catch (UnsupportedOperationException expected) {
                if (!expected.getMessage().contains("32-bit range")) throw expected;
            }
            glDrawArraysInstanced(GL_TRIANGLE_STRIP, 4, 4, 6);
            verify(texture, 0, 2, 3, 4);
            glDrawArraysInstancedBaseInstance(GL_TRIANGLE_STRIP, 4, 0, 6, -1);
            glDrawArraysInstancedBaseInstance(GL_TRIANGLE_STRIP, 4, 4, 0, -1);
            verify(texture, 0, 2, 3, 4);
            // Legacy pointer repair must run before applying the scoped base offset.
            glBindBuffer(GL_ARRAY_BUFFER, colors);
            GL20.glVertexAttribPointer(2, 1, GL_FLOAT, false, 8, 8L);
            GL20.glVertexAttribPointer(3, 1, GL_FLOAT, false, 8, 12L);
            GL33.glVertexAttribDivisor(2, 2);
            GL33.glVertexAttribDivisor(3, 2);
            glDrawArraysInstancedBaseInstance(GL_TRIANGLE_STRIP, 4, 4, 6, 2);
            verify(texture, 2, 2, 3, 4);
            glDrawArraysInstanced(GL_TRIANGLE_STRIP, 4, 4, 6);
            verify(texture, 0, 2, 3, 4);
            glUniform1i(glGetUniformLocation(program, "quads"), 1);
            glDrawArraysInstancedBaseInstance(GL_QUADS, 4, 4, 6, 2);
            verify(texture, 2, 2, 3, 4);
            Files.writeString(
                    output.resolve("base-instance.txt"),
                    "PASS\nverified_pixels=960\nchecks=base 0/1/2/3,divisors 1/2/3,base after division,zero-based instance ID,nonzero first vertex,shared/independent bindings,ordinary draw restoration,unsigned overflow,zero-count draws,legacy pointers,quad conversion\n");
        } finally {
            glUseProgram(0);
            glBindVertexArray(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteVertexArrays(vao);
            glDeleteBuffers(colors);
            glDeleteBuffers(alpha);
            glDeleteBuffers(blue);
            glDeleteTextures(texture);
            glDeleteFramebuffers(fbo);
            glDeleteProgram(program);
        }
    }

    private static int red(int record) {
        return 10 + record * 15;
    }

    private static int green(int record) {
        return 200 - record * 11;
    }

    private static int opacity(int record) {
        return 250 - record * 9;
    }

    private static void verify(int target, int base, int divisor, int alphaDivisor, int first) {
        var pixels = ResourceReadback.layer(target, 0);
        for (int y = 0; y < 4; y++)
            for (int x = 0; x < 24; x++) {
                int instance = x / 4, at = (y * 24 + x) * 4;
                int[] expected = {
                    red(base + instance / divisor),
                    green(base + instance / divisor),
                    first == 0 ? 51 : 204,
                    opacity(base + instance / alphaDivisor)
                };
                for (int c = 0; c < 4; c++)
                    if (Math.abs((pixels[at + c] & 255) - expected[c]) > 1)
                        throw new IllegalStateException("Base-instance mismatch base=" + base + " divisor=" + divisor
                                + " instance=" + instance + " channel=" + c + " actual=" + (pixels[at + c] & 255)
                                + " expected=" + expected[c]);
            }
    }

    private static int program() {
        int p = glCreateProgram(), v = glCreateShader(GL_VERTEX_SHADER), f = glCreateShader(GL_FRAGMENT_SHADER);
        try {
            glShaderSource(
                    v,
                    "#version 430 core\nlayout(location=1)in float blue;layout(location=2)in float red;layout(location=3)in float green;layout(location=4)in float alpha;flat out vec4 shade;uniform int quads;void main(){int id=gl_VertexID&3;vec2 p=vec2(quads!=0?(id^(id>>1))&1:id&1,(id>>1)&1);gl_Position=vec4((float(gl_InstanceID)+p.x)/3.-1.,p.y*2.-1.,0,1);shade=vec4(red,green,blue,alpha);}");
            glShaderSource(f, "#version 430 core\nflat in vec4 shade;out vec4 color;void main(){color=shade;}");
            for (int shader : new int[] {v, f}) {
                glCompileShader(shader);
                if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0)
                    throw new IllegalStateException(glGetShaderInfoLog(shader));
                glAttachShader(p, shader);
            }
            glLinkProgram(p);
            if (glGetProgrami(p, GL_LINK_STATUS) == 0) throw new IllegalStateException(glGetProgramInfoLog(p));
            return p;
        } finally {
            glDeleteShader(v);
            glDeleteShader(f);
        }
    }
}
