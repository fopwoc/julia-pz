package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import org.lwjgl.system.MemoryStack;

/** Conditional legacy shaders with a sampler whose name collides with modern texture functions. */
final class ShaderCompatibilityChecks {
    private ShaderCompatibilityChecks() {}

    static void run() {
        int vertex = glCreateShader(GL_VERTEX_SHADER), fragment = glCreateShader(GL_FRAGMENT_SHADER);
        int program = glCreateProgram(), image = glGenTextures(), target = glGenTextures(), fbo = glGenFramebuffers();
        int mask = glGenTextures();
        try (var stack = MemoryStack.stackPush()) {
            glShaderSource(vertex, """
                    #version 330 core
                    void main() {
                        vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                        gl_Position = vec4(p * 2.0 - 1.0, 0, 1);
                    }
                    """);
            glShaderSource(fragment, """
                    #version 110
                    uniform sampler2D texture;
                    uniform sampler2D CutawayStencil;
                    #if 1
                    uniform float amount = 1.0;
                    #else
                    float amount = 0.0;
                    #endif
                    float strength() {
                    #ifdef QUALITY
                        return 0.0;
                    #else
                        return amount;
                    #endif
                    }
                    void main() { gl_FragColor = texture2D(texture, vec2(0.5)) * texture2D(CutawayStencil, vec2(0.5)).r * strength(); }
                    """);
            for (int shader : new int[] {vertex, fragment}) {
                glCompileShader(shader);
                if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
                    throw new IllegalStateException("Conditional compatibility shader: " + glGetShaderInfoLog(shader));
                glAttachShader(program, shader);
            }
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
                throw new IllegalStateException(glGetProgramInfoLog(program));
            glUseProgram(program);
            int sampler = glGetUniformLocation(program, "texture"), amount = glGetUniformLocation(program, "amount");
            if (sampler < 0 || amount < 0) throw new IllegalStateException("Legacy shader lost public uniform names");
            // PZ caches reflected names before overriding sampler units by their source names.
            var uniforms = new HashMap<String, Integer>();
            for (int i = 0; i < glGetProgrami(program, GL_ACTIVE_UNIFORMS); i++) {
                var size = stack.mallocInt(1);
                var type = stack.mallocInt(1);
                String name = glGetActiveUniform(program, i, 128, size, type);
                int location = glGetUniformLocation(program, name);
                uniforms.put(name, location);
                if (type.get(0) == GL_SAMPLER_2D) glUniform1i(location, 1);
            }
            if (uniforms.containsKey("texture")) glUniform1i(uniforms.get("texture"), 0);
            glUniform1i(uniforms.get("CutawayStencil"), 1);
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, mask);
            glTexImage2D(
                    GL_TEXTURE_2D,
                    0,
                    GL_RGBA8,
                    1,
                    1,
                    0,
                    GL_RGBA,
                    GL_UNSIGNED_BYTE,
                    stack.bytes((byte) 255, (byte) 0, (byte) 0, (byte) 255));
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, target);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 8, 8, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Incomplete compatibility framebuffer");
            glBindTexture(GL_TEXTURE_2D, image);
            glTexImage2D(
                    GL_TEXTURE_2D,
                    0,
                    GL_RGBA8,
                    1,
                    1,
                    0,
                    GL_RGBA,
                    GL_UNSIGNED_BYTE,
                    stack.bytes((byte) 51, (byte) 102, (byte) 153, (byte) 255));
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glViewport(0, 0, 8, 8);
            glDisable(GL_BLEND);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_CULL_FACE);
            glDisable(GL_SCISSOR_TEST);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            var pixels = ResourceReadback.layer(target, 0);
            int[] expected = {51, 102, 153, 255};
            for (int i = 0; i < 64; i++)
                for (int c = 0; c < 4; c++)
                    if ((pixels[i * 4 + c] & 255) != expected[c])
                        throw new IllegalStateException(
                                "Compatibility pixel " + i + " channel " + c + " got " + (pixels[i * 4 + c] & 255));
            String genuine = """
                    #version 330 core
                    uniform sampler2D gtexture;
                    out vec4 color;
                    void main() { color = texture(gtexture, vec2(0.5)); }
                    """;
            glShaderSource(fragment, genuine);
            for (int revision = 0; revision < 2; revision++) {
                if (revision == 1) {
                    glUseProgram(0);
                    glDeleteProgram(program);
                    program = glCreateProgram();
                    glCompileShader(fragment);
                    glAttachShader(program, vertex);
                    glAttachShader(program, fragment);
                }
                // Changing source without compiling must retain names from the compiled snapshot.
                glLinkProgram(program);
                if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
                    throw new IllegalStateException(glGetProgramInfoLog(program));
                String expectedName = revision == 0 ? "texture" : "gtexture";
                int found = -1, longest = 0;
                var reflectedNames = new ArrayList<String>();
                for (int i = 0; i < glGetProgrami(program, GL_ACTIVE_UNIFORMS); i++) {
                    String reflected = glGetActiveUniform(program, i, 128, stack.mallocInt(1), stack.mallocInt(1));
                    reflectedNames.add(reflected);
                    longest = Math.max(longest, reflected.length() + 1);
                    if (reflected.equals(expectedName)) found = i;
                }
                if (found < 0)
                    throw new IllegalStateException(
                            "Lost public uniform name after relink: " + expectedName + " names=" + reflectedNames);
                if (glGetProgrami(program, GL_ACTIVE_UNIFORM_MAX_LENGTH) != longest)
                    throw new IllegalStateException("Wrong reflected uniform name length");
                var name = stack.malloc(7);
                name.position(2);
                var length = stack.mallocInt(2);
                length.position(1);
                glGetActiveUniform(program, found, length, stack.mallocInt(1), stack.mallocInt(1), name);
                if (length.get(1) != 4 || name.get(6) != 0 || name.position() != 2 || length.position() != 1)
                    throw new IllegalStateException("Public uniform reflection changed output positions or truncation");
                for (int i = 0; i < 4; i++)
                    if (name.get(2 + i) != expectedName.charAt(i))
                        throw new IllegalStateException("Public uniform name was truncated before remapping");
            }
        } finally {
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glBindTexture(GL_TEXTURE_2D, 0);
            glDeleteFramebuffers(fbo);
            glDeleteTextures(target);
            glDeleteTextures(image);
            glDeleteTextures(mask);
            glDeleteProgram(program);
            glDeleteShader(vertex);
            glDeleteShader(fragment);
        }
    }
}
