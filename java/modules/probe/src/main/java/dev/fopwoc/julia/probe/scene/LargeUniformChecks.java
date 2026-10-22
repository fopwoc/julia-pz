package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL42.glTexStorage2D;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Both shader stages must see uniforms past the first 4 KiB of a default block. */
final class LargeUniformChecks {
    private static final int COUNT = 600;

    private LargeUniformChecks() {}

    static void run(Path output) throws IOException {
        int program = program(), target = glGenTextures(), fbo = glGenFramebuffers();
        try {
            glBindTexture(GL_TEXTURE_2D, target);
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, 1, 1);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            glViewport(0, 0, 1, 1);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_BLEND);
            glDisable(GL_SCISSOR_TEST);
            glUseProgram(program);
            float[] values = new float[COUNT * 4];
            for (int i = 0; i < values.length; i++) values[i] = .25f;
            glUniform4fv(glGetUniformLocation(program, "vertexValues"), values);
            glUniform4fv(glGetUniformLocation(program, "fragmentValues"), values);
            glUniform1i(glGetUniformLocation(program, "index"), COUNT - 1);
            glUniform3f(glGetUniformLocation(program, "vertexTail"), 1, 1, 1);
            for (float value : new float[] {.25f, .75f}) {
                glUniform3f(glGetUniformLocation(program, "tail"), value, value, value);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                byte[] pixels = ResourceReadback.layer(target, 0);
                int[] expected = {64, 64, Math.round(value * 255), 255};
                for (int c = 0; c < 4; c++)
                    if (Math.abs((pixels[c] & 255) - expected[c]) > 1)
                        throw new IllegalStateException("Large uniform channel " + c + ": got " + (pixels[c] & 255)
                                + " expected " + expected[c]);
            }
            Files.writeString(
                    output.resolve("large-uniforms.txt"),
                    "PASS\nchecks=9600-byte vertex and fragment arrays,tail uniform,updated tail\n");
        } finally {
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteProgram(program);
            glDeleteFramebuffers(fbo);
            glDeleteTextures(target);
        }
    }

    private static int program() {
        String vertex = """
            #version 430 core
            uniform vec4 vertexValues[600];
            uniform vec3 vertexTail;
            uniform int index;
            out float value;
            void main() {
                vec2 p=vec2((gl_VertexID<<1)&2, gl_VertexID&2);
                gl_Position=vec4(p*2.-1.,0,1);
                value=vertexValues[index].r*vertexTail.r;
            }
            """;
        String fragment = """
            #version 430 core
            uniform vec4 fragmentValues[600];
            uniform vec3 tail;
            uniform int index;
            in float value;
            out vec4 colour;
            void main() { colour=vec4(value,fragmentValues[index].g,tail.b,1); }
            """;
        int program = glCreateProgram();
        for (int type : new int[] {GL_VERTEX_SHADER, GL_FRAGMENT_SHADER}) {
            int shader = glCreateShader(type);
            try {
                glShaderSource(shader, type == GL_VERTEX_SHADER ? vertex : fragment);
                glCompileShader(shader);
                if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0)
                    throw new IllegalStateException(glGetShaderInfoLog(shader));
                glAttachShader(program, shader);
            } finally {
                glDeleteShader(shader);
            }
        }
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == 0) throw new IllegalStateException(glGetProgramInfoLog(program));
        return program;
    }
}
