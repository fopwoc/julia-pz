package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryStack;

/** Real GLSL object linking, shared libraries, compile snapshots, relinks, and deferred deletion. */
final class ShaderLinkChecks {
    private static final String VERTEX_LIBRARY = """
            #version 330 core
            vec2 position(int id) {
                return vec2((id << 1) & 2, id & 2) * 2.0 - 1.0;
            }
            """;
    private static final String VERTEX_MAIN = """
            #version 330 core
            vec2 position(int id);
            void main() { gl_Position = vec4(position(gl_VertexID), 0, 1); }
            """;
    private static final String LIBRARY_BODY = """
            #define AMOUNT 0.1
            uniform vec4 tint;
            float max(float a, float b) { return a + b; }
            float clamp(float v, float min, float max) { return v + min + max; }
            float amount() { return AMOUNT; }
            """;
    private static final String MAIN_BODY = """
            #define AMOUNT 0.6
            uniform vec4 tint;
            float max(float a, float b);
            float clamp(float v, float min, float max);
            float amount();
            out vec4 color;
            void main() {
                color = tint * vec4(max(amount(), 0.1), clamp(0.1, 0.1, 0.2),
                                    max(vec2(AMOUNT), vec2(0.2)).x, 1.0);
            }
            """;

    private ShaderLinkChecks() {}

    static void run() {
        int vertexLibrary = glCreateShader(GL_VERTEX_SHADER), vertex = glCreateShader(GL_VERTEX_SHADER);
        int library = glCreateShader(GL_FRAGMENT_SHADER), fragment = glCreateShader(GL_FRAGMENT_SHADER);
        int combined = glCreateShader(GL_FRAGMENT_SHADER), invalid = glCreateShader(GL_FRAGMENT_SHADER);
        int first = glCreateProgram(),
                second = glCreateProgram(),
                single = glCreateProgram(),
                broken = glCreateProgram();
        int target = glGenTextures(), fbo = glGenFramebuffers();
        try {
            compile(vertexLibrary, VERTEX_LIBRARY);
            compile(vertex, VERTEX_MAIN);
            compile(library, "#version 330 core\n" + LIBRARY_BODY);
            compile(fragment, "#version 330 core\n" + MAIN_BODY);
            compile(
                    combined,
                    "#version 330 core\n" + LIBRARY_BODY + "\n#undef AMOUNT\n"
                            + MAIN_BODY.replace("uniform vec4 tint;", ""));
            for (int shader : new int[] {vertexLibrary, vertex, library, fragment}) glAttachShader(first, shader);
            for (int shader : new int[] {fragment, library, vertex, vertexLibrary}) glAttachShader(second, shader);
            for (int shader : new int[] {vertexLibrary, vertex, combined}) glAttachShader(single, shader);
            glDeleteShader(library);
            glDeleteShader(vertexLibrary);
            if (!glIsShader(library)
                    || glGetShaderi(library, GL_DELETE_STATUS) != GL_TRUE
                    || glGetProgrami(first, GL_ATTACHED_SHADERS) != 4)
                throw new IllegalStateException("Attached library deletion was not deferred");
            try (var stack = MemoryStack.stackPush()) {
                var count = stack.ints(-7, -8).position(1);
                var names = stack.ints(-9, 0, 0, 0, 0).position(1);
                glGetAttachedShaders(first, count, names);
                int[] expected = {vertexLibrary, vertex, library, fragment};
                if (count.position() != 1
                        || names.position() != 1
                        || count.get(0) != -7
                        || count.get(1) != 4
                        || names.get(0) != -9)
                    throw new IllegalStateException("Attachment queries changed caller buffer state");
                for (int i = 0; i < 4; i++)
                    if (names.get(i + 1) != expected[i])
                        throw new IllegalStateException("Internal stage shader escaped into attachment query");
            }
            // Replacing source must not replace the last compiled object until glCompileShader.
            glShaderSource(library, "not valid GLSL");
            if (!glGetShaderSource(library, 64).stripTrailing().endsWith("not valid GLSL")
                    || glGetShaderi(library, GL_COMPILE_STATUS) != GL_TRUE)
                throw new IllegalStateException("Shader source replacement changed the compiled snapshot: source="
                        + glGetShaderSource(library, 1024) + "; status=" + glGetShaderi(library, GL_COMPILE_STATUS));
            link(first);
            link(second);
            link(single);
            glBindTexture(GL_TEXTURE_2D, target);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 8, 8, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Incomplete shader-link framebuffer");
            glViewport(0, 0, 8, 8);
            glDisable(GL_BLEND);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_CULL_FACE);
            glDisable(GL_SCISSOR_TEST);
            draw(first, target, 51, 102, 153);
            draw(second, target, 51, 102, 153);
            // The combined object has one preprocessor scope, so amount() retains its own definition.
            draw(single, target, 51, 102, 153);
            glDetachShader(first, library);
            glLinkProgram(first);
            if (glGetProgrami(first, GL_LINK_STATUS) != GL_FALSE
                    || glGetProgramInfoLog(first).isBlank())
                throw new IllegalStateException("Unresolved helper function linked successfully");
            glAttachShader(first, library);
            link(first);
            glUseProgram(first);
            glDeleteProgram(first);
            if (!glIsProgram(first)) throw new IllegalStateException("Bound program was deleted before unbinding");
            glUseProgram(second);
            if (glIsProgram(first) || !glIsShader(library))
                throw new IllegalStateException("Shared helper did not survive the first program's release");
            compile(
                    library,
                    "#version 330 core\n"
                            + LIBRARY_BODY
                                    .replace("return a + b;", "return a + b + 0.6;")
                                    .replace("return v + min + max;", "return v + min + max - 0.2;"));
            link(second);
            draw(second, target, 204, 51, 153);
            glDetachShader(second, library);
            if (glIsShader(library)) throw new IllegalStateException("Deleted library survived its final detach");
            glShaderSource(invalid, "#version 330 core\nvoid broken( {");
            glCompileShader(invalid);
            if (glGetShaderi(invalid, GL_COMPILE_STATUS) != GL_FALSE
                    || glGetShaderInfoLog(invalid).isBlank())
                throw new IllegalStateException("Invalid shader syntax was accepted");
            glAttachShader(broken, vertex);
            glAttachShader(broken, invalid);
            glLinkProgram(broken);
            if (glGetProgrami(broken, GL_LINK_STATUS) != GL_FALSE)
                throw new IllegalStateException("Program with failed shader linked successfully");
            storageBoundary();
        } finally {
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glBindTexture(GL_TEXTURE_2D, 0);
            for (int program : new int[] {first, second, single, broken}) glDeleteProgram(program);
            for (int shader : new int[] {vertexLibrary, vertex, library, fragment, combined, invalid})
                glDeleteShader(shader);
            glDeleteFramebuffers(fbo);
            glDeleteTextures(target);
        }
    }

    private static void storageBoundary() {
        int library = glCreateShader(GL_FRAGMENT_SHADER), fragment = glCreateShader(GL_FRAGMENT_SHADER);
        int program = glCreateProgram();
        try {
            compile(library, """
                    #version 430 core
                    layout(std430, binding = 2) readonly buffer Data { vec4 value; };
                    vec4 readValue() { return value; }
                    """);
            compile(fragment, """
                    #version 430 core
                    vec4 readValue();
                    out vec4 color;
                    void main() { color = readValue(); }
                    """);
            glAttachShader(program, library);
            glAttachShader(program, fragment);
            try {
                glLinkProgram(program);
                throw new IllegalStateException(
                        "Storage-block translation failure was returned as a recoverable link error");
            } catch (UnsupportedOperationException expected) {
                if (!expected.getMessage().contains("resource names must be preserved")
                        || glGetProgrami(program, GL_LINK_STATUS) != GL_FALSE
                        || !glGetProgramInfoLog(program).contains("resource names must be preserved"))
                    throw new IllegalStateException("Storage-block failure lost its diagnostic", expected);
            }
        } finally {
            glDeleteProgram(program);
            glDeleteShader(library);
            glDeleteShader(fragment);
        }
    }

    private static void draw(int program, int target, int red, int green, int blue) {
        glUseProgram(program);
        int tint = glGetUniformLocation(program, "tint");
        if (tint < 0) throw new IllegalStateException("Linked shared uniform lost its original name");
        glUniform4f(tint, 1, 1, 1, 1);
        glClearColor(0, 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        var pixels = ResourceReadback.layer(target, 0);
        int[] expected = {red, green, blue, 255};
        for (int i = 0; i < 64; i++)
            for (int channel = 0; channel < 4; channel++)
                if (Math.abs((pixels[i * 4 + channel] & 255) - expected[channel]) > 1)
                    throw new IllegalStateException("Linked shader pixel " + i + " channel " + channel + " expected "
                            + expected[channel] + " got " + (pixels[i * 4 + channel] & 255));
    }

    private static void compile(int shader, String source) {
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
            throw new IllegalStateException(glGetShaderInfoLog(shader));
    }

    private static void link(int program) {
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
            throw new IllegalStateException(glGetProgramInfoLog(program));
    }
}
