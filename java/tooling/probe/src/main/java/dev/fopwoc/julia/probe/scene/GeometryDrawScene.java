package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL32.*;
import static org.lwjgl.opengl.GL41.glViewportIndexedf;
import static org.lwjgl.opengl.GL43.*;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

/** Exercises geometry through public GL shader, uniform, VAO and draw entry points. */
public final class GeometryDrawScene {
    private GeometryDrawScene() {}

    private static final String VERTEX = """
            #version 460 core
            layout(location=0) in vec2 position;
            layout(location=2) in vec4 tint;
            layout(location=9) in ivec4 joints;
            uniform float gain;
            out vec2 incoming;
            void main() {
                incoming=vec2(tint.r,gain);
                gl_Position=vec4(position+vec2(joints.x)*0.001,0,1);
            }
            """;
    private static final String GEOMETRY = """
            #version 460 core
            layout(triangles,invocations=6) in;
            layout(triangle_strip,max_vertices=3) out;
            uniform mat4 faces[6];
            uniform float gain;
            in vec2 incoming[];
            out vec2 outgoing;
            flat out int shade;
            void main() {
                for(int i=0;i<3;i++) {
                    outgoing=incoming[i]+vec2(0,gain);
                    shade=i+1;
                    gl_Position=faces[gl_InvocationID]*gl_in[i].gl_Position;
                    gl_ViewportIndex=gl_InvocationID;
                    gl_PrimitiveID=7+gl_InvocationID;
                    EmitVertex();
                }
                EndPrimitive();
            }
            """;
    private static final String FRAGMENT = """
            #version 460 core
            uniform float gain;
            uniform sampler2D diffuse;
            in vec2 outgoing;
            flat in int shade;
            out vec4 color;
            void main() {color=vec4(outgoing+vec2(float(shade)/255.0,0),float(gl_PrimitiveID)/255.0+gain,1)*texelFetch(diffuse,ivec2(0),0);}
            """;

    public static void run() {
        int program = program();
        int texture = glGenTextures(), fbo = glGenFramebuffers(), vao = glGenVertexArrays(), white = glGenTextures();
        int vbo = glGenBuffers(), ebo = glGenBuffers(), sentinel = glGenBuffers();
        var bytes = MemoryUtil.memCalloc(48);
        var indices = MemoryUtil.memAllocInt(4);
        var matrices = MemoryUtil.memCallocFloat(96);
        try {
            for (int i = 0; i < 3; i++) {
                int at = (i + 1) * 12;
                bytes.putFloat(at, i == 1 ? 3 : -1).putFloat(at + 4, i == 2 ? 3 : -1);
                bytes.put(at + 8, (byte) 64).put(at + 11, (byte) 255);
            }
            indices.put(new int[] {0, 1, 2, 0}).flip();
            for (int face = 0; face < 6; face++) for (int i = 0; i < 4; i++) matrices.put(face * 16 + i * 5, 1);
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 24, 16, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
            require(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE, "framebuffer");
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, bytes, GL_STATIC_DRAW);
            glVertexAttribPointer(0, 2, GL_FLOAT, false, 12, 0L);
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(2, 4, GL_UNSIGNED_BYTE, true, 12, 8L);
            glEnableVertexAttribArray(2);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, sentinel);
            glBufferData(GL_SHADER_STORAGE_BUFFER, 256L, GL_STATIC_DRAW);
            for (int i = 0; i < 9; i++) glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, sentinel);
            glBindBufferRange(GL_UNIFORM_BUFFER, 4, sentinel, 0, 16);
            glViewport(0, 0, 24, 16);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_CULL_FACE);
            glDisable(GL_BLEND);
            glDisable(GL_SCISSOR_TEST);
            glDisable(GL_STENCIL_TEST);
            glColorMask(true, true, true, true);
            glClearColor(0, 0, 0, 0);
            glClear(GL_COLOR_BUFFER_BIT);
            for (int i = 0; i < 6; i++) glViewportIndexedf(i, (i % 3) * 8, (i / 3) * 8, 8, 8);
            glActiveTexture(GL_TEXTURE0 + 5);
            glBindTexture(GL_TEXTURE_2D, white);
            var pixel = MemoryUtil.memAlloc(4);
            try {
                pixel.putInt(0, -1);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
            } finally {
                MemoryUtil.memFree(pixel);
            }
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glUseProgram(program);
            glUniform1i(glGetUniformLocation(program, "diffuse"), 5);
            require(glGetProgrami(program, GL_GEOMETRY_VERTICES_OUT) == 3, "geometry introspection");
            glUniformMatrix4fv(glGetUniformLocation(program, "faces"), false, matrices);
            int gain = glGetUniformLocation(program, "gain");
            require(gain >= 0, "shared uniform");
            glUniform1f(gain, .125f);
            glDrawArrays(GL_TRIANGLES, 1, 3);
            checkPixels(texture, .125f);
            require(glGetInteger(GL_CURRENT_PROGRAM) == program, "program restoration");
            require(glGetInteger(GL_ACTIVE_TEXTURE) == GL_TEXTURE0 + 5, "texture unit restoration");
            require(glGetInteger(GL_VERTEX_ARRAY_BINDING) == vao, "VAO restoration");
            for (int i = 0; i < 9; i++)
                require(glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i) == sentinel, "SSBO restoration " + i);
            require(glGetIntegeri(GL_UNIFORM_BUFFER_BINDING, 4) == sentinel, "UBO restoration");
            glUniform1f(gain, .25f);
            glDrawElementsBaseVertex(GL_TRIANGLES, 3, GL_UNSIGNED_INT, 0L, 1);
            checkPixels(texture, .25f);
            boolean rejected = false;
            try {
                glDrawArrays(GL_TRIANGLES, 100, 3);
            } catch (IllegalStateException expected) {
                rejected = expected.getMessage().contains("fetch out of bounds");
            }
            require(rejected, "GPU bounds failure");
            require(glGetInteger(GL_CURRENT_PROGRAM) == program, "failed draw restoration");
            glLinkProgram(program);
            require(glGetProgrami(program, GL_LINK_STATUS) != 0, "relink");
            glUniform1i(glGetUniformLocation(program, "diffuse"), 5);
            gain = glGetUniformLocation(program, "gain");
            glUniformMatrix4fv(glGetUniformLocation(program, "faces"), false, matrices);
            glUniform1f(gain, .25f);
            glDrawArrays(GL_TRIANGLES, 1, 3);
            checkPixels(texture, .25f);
            glUseProgram(0);
            glUseProgram(program);
            glDeleteProgram(program); // A current, deleted program must survive the internal program switches.
            glDrawArrays(GL_TRIANGLES, 1, 3);
            checkPixels(texture, .25f);
            glUseProgram(0);
            program = 0;
        } finally {
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glBindVertexArray(0);
            for (int i = 0; i < 9; i++) glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, 0);
            glBindBufferBase(GL_UNIFORM_BUFFER, 4, 0);
            for (int buffer : new int[] {vbo, ebo, sentinel}) glDeleteBuffers(buffer);
            glDeleteVertexArrays(vao);
            glDeleteFramebuffers(fbo);
            glDeleteTextures(texture);
            glDeleteTextures(white);
            glActiveTexture(GL_TEXTURE0);
            if (program != 0) glDeleteProgram(program);
            MemoryUtil.memFree(bytes);
            MemoryUtil.memFree(indices);
            MemoryUtil.memFree(matrices);
        }
    }

    private static void checkPixels(int texture, float gain) {
        var pixels = ResourceReadback.layer(texture, 0);
        for (int face = 0; face < 6; face++) {
            int at = (((face / 3) * 8 + 3) * 24 + (face % 3) * 8 + 3) * 4;
            require((pixels[at] & 255) == 67, "normalized vertex fetch face " + face + ": " + (pixels[at] & 255));
            require(
                    Math.abs((pixels[at + 1] & 255) - Math.round(gain * 2 * 255)) <= 1,
                    "vertex/geometry shared uniform face " + face + ": " + (pixels[at + 1] & 255));
            require(
                    Math.abs((pixels[at + 2] & 255) - Math.round(7 + face + gain * 255)) <= 1,
                    "fragment uniform/primitive ID face " + face + ": " + (pixels[at + 2] & 255));
            require((pixels[at + 3] & 255) == 255, "viewport route face " + face);
        }
    }

    private static int program() {
        int program = glCreateProgram();
        int[] stages = {GL_VERTEX_SHADER, GL_GEOMETRY_SHADER, GL_FRAGMENT_SHADER};
        String[] sources = {VERTEX, GEOMETRY, FRAGMENT};
        try {
            for (int i = 0; i < stages.length; i++) {
                int shader = glCreateShader(stages[i]);
                try {
                    glShaderSource(shader, sources[i]);
                    glCompileShader(shader);
                    require(glGetShaderi(shader, GL_COMPILE_STATUS) != 0, glGetShaderInfoLog(shader));
                    glAttachShader(program, shader);
                } finally {
                    glDeleteShader(shader);
                }
            }
            glLinkProgram(program);
            require(glGetProgrami(program, GL_LINK_STATUS) != 0, glGetProgramInfoLog(program));
            return program;
        } catch (RuntimeException | Error failure) {
            glDeleteProgram(program);
            throw failure;
        }
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("Geometry GL integration: " + label);
    }
}
