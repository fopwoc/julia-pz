package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.*;
import static org.lwjgl.opengl.GL43.*;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BiConsumer;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;

/** GPU fetches distinguish shared bindings, full-width divisors, legacy rebinding, and VAO ownership. */
final class DivisorChecks {
    private DivisorChecks() {}

    static void run(Path output) throws IOException {
        Files.deleteIfExists(output.resolve("divisors.txt"));
        int vertex = glCreateShader(GL_VERTEX_SHADER), fragment = glCreateShader(GL_FRAGMENT_SHADER);
        int program = glCreateProgram(), buffer = glGenBuffers(), indices = glGenBuffers();
        int target = glGenTextures(), fbo = glGenFramebuffers();
        int first = glGenVertexArrays(), second = glGenVertexArrays();
        var data = MemoryUtil.memAlloc(16 + 6 * 20);
        try {
            glShaderSource(vertex, """
                    #version 430 core
                    layout(location=2) in vec4 tint;
                    layout(location=5) in float green;
                    uniform int perVertex;
                    flat out vec3 shade;
                    void main() {
                        vec2 p = vec2(gl_VertexID & 1, (gl_VertexID >> 1) & 1);
                        gl_Position = vec4((float(gl_InstanceID) + p.x) / 3.0 - 1.0, p.y * 2.0 - 1.0, 0, 1);
                        shade = vec3(tint.r, green, 0.6);
                        if (perVertex != 0) {
                            float r = float(gl_VertexID + 1) * 0.2;
                            float g = float(gl_VertexID % 3 + 1) * 0.2;
                            shade = abs(tint.r-r) < 0.001 && abs(green-g) < 0.001 ? vec3(0,1,0) : vec3(1,0,0);
                        }
                    }
                    """);
            glShaderSource(fragment, """
                    #version 430 core
                    flat in vec3 shade;
                    out vec4 color;
                    void main() { color = vec4(shade,1); }
                    """);
            for (int shader : new int[] {vertex, fragment}) {
                glCompileShader(shader);
                if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
                    throw new IllegalStateException(glGetShaderInfoLog(shader));
                glAttachShader(program, shader);
            }
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
                throw new IllegalStateException(glGetProgramInfoLog(program));
            glUseProgram(program);
            for (int i = 0; i < data.capacity(); i++) data.put(i, (byte) 0);
            for (int i = 0; i < 6; i++) {
                data.putFloat(16 + i * 20, red(i));
                data.putFloat(32 + i * 20, green(i));
            }
            glBindBuffer(GL_ARRAY_BUFFER, buffer);
            glBufferData(GL_ARRAY_BUFFER, data, GL_STATIC_DRAW);
            glBindTexture(GL_TEXTURE_2D, target);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 24, 4, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            glViewport(0, 0, 24, 4);
            glDisable(GL_BLEND);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_CULL_FACE);
            glDisable(GL_SCISSOR_TEST);
            configure(first, buffer);
            BiConsumer<Integer, Integer> set = GL43::glVertexBindingDivisor;
            for (int divisor : new int[] {1, 2, 3, 65538, 2, -1, 0}) {
                set.accept(6, divisor);
                glUniform1i(glGetUniformLocation(program, "perVertex"), divisor == 0 ? 1 : 0);
                if (divisor >= 0 && glGetIntegeri(GL_VERTEX_BINDING_DIVISOR, 6) != divisor)
                    throw new IllegalStateException("Binding divisor query lost VAO state");
                draw(target, divisor, divisor, false);
            }
            glUniform1i(glGetUniformLocation(program, "perVertex"), 0);
            set.accept(6, 2);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indices);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, new short[] {0, 1, 2, 2, 1, 3}, GL_STATIC_DRAW);
            draw(target, 2, 2, true);
            configure(second, buffer);
            set.accept(6, 3);
            draw(target, 3, 3, false);
            glBindVertexArray(first);
            draw(target, 2, 2, false);
            glBindVertexArray(second);
            draw(target, 3, 3, false);
            // Legacy divisor restores attribute 2's binding even when the GLSM cached value already matches.
            glBindVertexBuffer(2, buffer, 16, 20);
            GL33.glVertexAttribDivisor(2, 2);
            draw(target, 2, 3, false);
            glVertexAttribBinding(2, 6);
            GL33.glVertexAttribDivisor(2, 2);
            draw(target, 2, 3, false);
            try {
                set.accept(16, 1);
                throw new IllegalStateException("Invalid binding accepted");
            } catch (IllegalArgumentException expected) {
                if (glGetIntegeri(GL_VERTEX_BINDING_DIVISOR, 6) != 3)
                    throw new IllegalStateException("Invalid call mutated VAO");
            }
            Files.writeString(
                    output.resolve("divisors.txt"),
                    "PASS\nverified_pixels=1248\nchecks=shared bindings,divisors 0/1/2/3/65538/UINT_MAX,pipeline cache,indexed draws,VAO switching,legacy rebinding,queries,method handles,invalid state\n");
        } finally {
            glUseProgram(0);
            glBindVertexArray(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glBindTexture(GL_TEXTURE_2D, 0);
            glDeleteVertexArrays(first);
            glDeleteVertexArrays(second);
            glDeleteBuffers(buffer);
            glDeleteBuffers(indices);
            glDeleteFramebuffers(fbo);
            glDeleteTextures(target);
            glDeleteProgram(program);
            glDeleteShader(vertex);
            glDeleteShader(fragment);
            MemoryUtil.memFree(data);
        }
    }

    private static void configure(int vao, int buffer) {
        glBindVertexArray(vao);
        glBindVertexBuffer(6, buffer, 16, 20);
        glVertexAttribFormat(2, 4, GL_FLOAT, false, 0);
        glVertexAttribFormat(5, 1, GL_FLOAT, false, 16);
        glVertexAttribBinding(2, 6);
        glVertexAttribBinding(5, 6);
        glEnableVertexAttribArray(2);
        glEnableVertexAttribArray(5);
    }

    private static void draw(int target, int redDivisor, int greenDivisor, boolean indexed) {
        glClearColor(0, 0, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT);
        if (indexed) glDrawElementsInstanced(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, 0L, 6);
        else glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, 6);
        byte[] pixels = ResourceReadback.layer(target, 0);
        for (int y = 0; y < 4; y++)
            for (int x = 0; x < 24; x++) {
                int instance = x / 4;
                int r = redDivisor == 0 ? 0 : Math.round(red(record(instance, redDivisor)) * 255);
                int g = greenDivisor == 0 ? 255 : Math.round(green(record(instance, greenDivisor)) * 255);
                int b = redDivisor == 0 ? 0 : 153;
                int offset = (y * 24 + x) * 4;
                int[] expected = {r, g, b, 255};
                for (int c = 0; c < 4; c++)
                    if (Math.abs(Byte.toUnsignedInt(pixels[offset + c]) - expected[c]) > 1)
                        throw new IllegalStateException("Divisor " + Integer.toUnsignedString(redDivisor) + "/"
                                + Integer.toUnsignedString(greenDivisor) + " indexed=" + indexed + " pixel=" + x + ","
                                + y + " channel=" + c + " expected=" + expected[c] + " actual="
                                + Byte.toUnsignedInt(pixels[offset + c]));
            }
    }

    private static int record(int instance, int divisor) {
        return Integer.divideUnsigned(instance, divisor);
    }

    private static float red(int record) {
        return record == 5 ? 0.1f : (record + 1) * 0.2f;
    }

    private static float green(int record) {
        return (record % 3 + 1) * 0.2f;
    }
}
