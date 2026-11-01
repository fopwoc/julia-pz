package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.glDrawRangeElements;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryStack;

/** Both triangles of two ranged indexed quads, using each index width and a nonzero EBO offset. */
final class QuadChecks {
    private QuadChecks() {}

    static void run() {
        int program = glCreateProgram(), vs = glCreateShader(GL_VERTEX_SHADER), fs = glCreateShader(GL_FRAGMENT_SHADER);
        int indices = glGenBuffers(), target = glGenTextures(), fbo = glGenFramebuffers();
        try (var stack = MemoryStack.stackPush()) {
            glShaderSource(vs, """
                    #version 330 core
                    void main() {
                        int id = gl_VertexID - 4;
                        vec2 corner = vec2(((id + 1) & 2) / 2, (id & 2) / 2);
                        gl_Position = vec4(corner.x + float(id / 4) - 1.0, corner.y * 2.0 - 1.0, 0, 1);
                    }
                    """);
            glShaderSource(fs, """
                    #version 330 core
                    out vec4 color;
                    void main() { color = vec4(0, 1, 0, 1); }
                    """);
            glCompileShader(vs);
            glCompileShader(fs);
            if (glGetShaderi(vs, GL_COMPILE_STATUS) == 0 || glGetShaderi(fs, GL_COMPILE_STATUS) == 0)
                throw new IllegalStateException(glGetShaderInfoLog(vs) + glGetShaderInfoLog(fs));
            glAttachShader(program, vs);
            glAttachShader(program, fs);
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == 0)
                throw new IllegalStateException(glGetProgramInfoLog(program));
            glUseProgram(program);
            glBindTexture(GL_TEXTURE_2D, target);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 8, 8, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Incomplete quad probe framebuffer");
            glViewport(0, 0, 8, 8);
            glDisable(GL_BLEND);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_SCISSOR_TEST);
            glDisable(GL_CULL_FACE);
            glClearColor(1, 0, 1, 1);
            for (int width : new int[] {2, 4, 1}) {
                int type = width == 1 ? GL_UNSIGNED_BYTE : width == 2 ? GL_UNSIGNED_SHORT : GL_UNSIGNED_INT;
                var data = stack.malloc(11 * width);
                for (int i = 0; i < 11; i++) {
                    int index = i < 3 ? 99 : i + 1;
                    switch (width) {
                        case 1 -> data.put((byte) index);
                        case 2 -> data.putShort((short) index);
                        case 4 -> data.putInt(index);
                        default -> throw new AssertionError(width);
                    }
                }
                data.flip();
                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indices);
                glBufferData(GL_ELEMENT_ARRAY_BUFFER, data, GL_STATIC_DRAW);
                glClear(GL_COLOR_BUFFER_BIT);
                glDrawRangeElements(GL_QUADS, 4, 11, 8, type, 3L * width);
                if (glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING) != indices)
                    throw new IllegalStateException("Quad conversion changed the caller's index binding");
                var pixels = ResourceReadback.layer(target, 0);
                for (int i = 0; i < 64; i++)
                    for (int channel = 0; channel < 4; channel++) {
                        int expected = channel == 1 || channel == 3 ? 255 : 0;
                        if ((pixels[i * 4 + channel] & 255) != expected)
                            throw new IllegalStateException("Missing quad pixel " + i + " channel " + channel + " with "
                                    + width + "-byte indices");
                    }
            }
        } finally {
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
            glBindTexture(GL_TEXTURE_2D, 0);
            glDeleteFramebuffers(fbo);
            glDeleteTextures(target);
            glDeleteBuffers(indices);
            glDeleteProgram(program);
            glDeleteShader(vs);
            glDeleteShader(fs);
        }
    }
}
