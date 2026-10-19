package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.glDrawRangeElements;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryStack;

/** Map-style streamed indexed quads with interleaved position/color/UV data and textured output. */
final class MapQuadChecks {
    private MapQuadChecks() {}

    static void run() {
        int vs = glCreateShader(GL_VERTEX_SHADER), fs = glCreateShader(GL_FRAGMENT_SHADER), program = glCreateProgram();
        int vertices = glGenBuffers(), indices = glGenBuffers(), image = glGenTextures(), target = glGenTextures();
        int fbo = glGenFramebuffers();
        try (var stack = MemoryStack.stackPush()) {
            glShaderSource(vs, """
                    #version 330 core
                    layout(location=0) in vec3 position;
                    layout(location=1) in vec4 color;
                    layout(location=2) in vec2 uv;
                    uniform mat4 ModelViewProjection;
                    out vec4 vColor;
                    out vec2 vUV;
                    void main() {
                        vColor = color;
                        vUV = uv;
                        gl_Position = ModelViewProjection * vec4(position, 1);
                    }
                    """);
            glShaderSource(fs, """
                    #version 120
                    uniform sampler2D DIFFUSE;
                    varying vec4 vColor;
                    varying vec2 vUV;
                    void main() {
                        vec4 texel = texture2D(DIFFUSE, vUV);
                        if (texel.a < 0.01) discard;
                        gl_FragColor = vColor * texel;
                    }
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
            glUniformMatrix4fv(
                    glGetUniformLocation(program, "ModelViewProjection"),
                    false,
                    stack.floats(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1));
            glUniform1i(glGetUniformLocation(program, "DIFFUSE"), 0);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, target);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 8, 8, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Incomplete map-quad framebuffer");
            glBindTexture(GL_TEXTURE_2D, image);
            glTexImage2D(
                    GL_TEXTURE_2D,
                    0,
                    GL_RGBA8,
                    2,
                    1,
                    0,
                    GL_RGBA,
                    GL_UNSIGNED_BYTE,
                    stack.bytes(
                            (byte) 255, (byte) 0, (byte) 0, (byte) 255, (byte) 0, (byte) 255, (byte) 0, (byte) 255));
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glViewport(0, 0, 8, 8);
            glDisable(GL_BLEND);
            glDisable(GL_CULL_FACE);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_SCISSOR_TEST);
            var data = stack.callocFloat(12 * 9);
            var elements = stack.shorts(
                    (short) 99,
                    (short) 99,
                    (short) 99,
                    (short) 4,
                    (short) 5,
                    (short) 6,
                    (short) 7,
                    (short) 8,
                    (short) 9,
                    (short) 10,
                    (short) 11);
            glBindBuffer(GL_ARRAY_BUFFER, vertices);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indices);
            for (int attrib = 0; attrib < 3; attrib++) glEnableVertexAttribArray(attrib);
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 36, 0L);
            glVertexAttribPointer(1, 4, GL_FLOAT, true, 36, 12L);
            glVertexAttribPointer(2, 2, GL_FLOAT, false, 36, 28L);
            for (int run = 0; run < 2; run++) {
                for (int id = 0; id < 8; id++) {
                    int local = id % 4, tile = id / 4;
                    int offset = (id + 4) * 9;
                    float x = ((local + 1) & 2) / 2 + tile - 1;
                    float y = (local & 2) - 1;
                    data.put(offset, x).put(offset + 1, y).put(offset + 2, 0);
                    for (int c = 0; c < 4; c++) data.put(offset + 3 + c, c == 3 || run == 0 ? 1 : 0.5f);
                    data.put(offset + 7, tile == 0 ? 0.25f : 0.75f).put(offset + 8, 0.5f);
                }
                glBufferData(GL_ARRAY_BUFFER, data, GL_STREAM_DRAW);
                glBufferData(GL_ELEMENT_ARRAY_BUFFER, elements, GL_STREAM_DRAW);
                glClearColor(0, 0, 1, 1);
                glClear(GL_COLOR_BUFFER_BIT);
                // Each map tile can be a distinct run, reusing the converter's scratch EBO.
                for (int tile = 0; tile < 2; tile++)
                    glDrawRangeElements(GL_QUADS, 4 + tile * 4, 8 + tile * 4, 4, GL_UNSIGNED_SHORT, 6L + tile * 8L);
                if (glGetInteger(GL_ARRAY_BUFFER_BINDING) != vertices
                        || glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING) != indices)
                    throw new IllegalStateException("Map quad conversion changed buffer bindings");
                var pixels = ResourceReadback.layer(target, 0);
                for (int i = 0; i < 64; i++)
                    for (int c = 0; c < 4; c++) {
                        int expected = c == 3 ? 255 : c == (i % 8 < 4 ? 0 : 1) ? run == 0 ? 255 : 128 : 0;
                        if ((pixels[i * 4 + c] & 255) != expected)
                            throw new IllegalStateException("Map quad run=" + run + " pixel=" + i + " channel=" + c
                                    + " got=" + (pixels[i * 4 + c] & 255) + " expected=" + expected);
                    }
            }
        } finally {
            for (int attrib = 0; attrib < 3; attrib++) glDisableVertexAttribArray(attrib);
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glBindTexture(GL_TEXTURE_2D, 0);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
            glDeleteFramebuffers(fbo);
            glDeleteTextures(target);
            glDeleteTextures(image);
            glDeleteBuffers(vertices);
            glDeleteBuffers(indices);
            glDeleteProgram(program);
            glDeleteShader(vs);
            glDeleteShader(fs);
        }
    }
}
