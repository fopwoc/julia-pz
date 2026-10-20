package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL42.GL_TEXTURE_IMMUTABLE_FORMAT;
import static org.lwjgl.opengl.GL42.glTexStorage3D;
import static org.lwjgl.opengl.GL43.GL_TEXTURE_IMMUTABLE_LEVELS;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryStack;

/** Bound immutable allocation preserves target/unit bindings and the distinct volume/array mip rules. */
final class BoundVolumeStorageChecks {
    private BoundVolumeStorageChecks() {}

    static void run(Path output) throws IOException {
        Files.deleteIfExists(output.resolve("bound-volume-storage.txt"));
        int target = GL45.glCreateTextures(GL_TEXTURE_2D), fbo = glGenFramebuffers();
        GL45.glTextureStorage2D(target, 1, GL_RGBA8, 2, 2);
        try (var stack = MemoryStack.stackPush()) {
            for (boolean array : new boolean[] {false, true}) {
                int kind = array ? GL_TEXTURE_2D_ARRAY : GL_TEXTURE_3D;
                int binding = array ? GL_TEXTURE_BINDING_2D_ARRAY : GL_TEXTURE_BINDING_3D;
                int texture = glGenTextures(), other = glGenTextures(), program = program(array);
                try {
                    glActiveTexture(GL_TEXTURE0 + 110);
                    glBindTexture(kind, texture);
                    glBindTexture(GL_TEXTURE_2D, other);
                    try {
                        glTexStorage3D(kind, 4, GL_RGBA8, 4, 4, 4);
                        throw new IllegalStateException("Too many immutable mip levels accepted");
                    } catch (IllegalArgumentException expected) {
                    }
                    glTexStorage3D(kind, 3, GL_RGBA8, 4, 4, 4);
                    if (glGetInteger(GL_ACTIVE_TEXTURE) != GL_TEXTURE0 + 110
                            || glGetInteger(binding) != texture
                            || glGetInteger(GL_TEXTURE_BINDING_2D) != other)
                        throw new IllegalStateException("Bound storage changed target or unit bindings");
                    if (glGetTexParameteri(kind, GL_TEXTURE_IMMUTABLE_FORMAT) != GL_TRUE
                            || glGetTexParameteri(kind, GL_TEXTURE_IMMUTABLE_LEVELS) != 3)
                        throw new IllegalStateException("Bound storage did not publish immutable levels");
                    try {
                        glTexStorage3D(kind, 3, GL_RGBA8, 4, 4, 4);
                        throw new IllegalStateException("Immutable allocation was redefined");
                    } catch (IllegalStateException expected) {
                        if (!expected.getMessage().contains("Cannot redefine")) throw expected;
                    }
                    for (int level = 0; level < 3; level++) {
                        int size = 4 >> level, layers = array ? 4 : size;
                        if (glGetTexLevelParameteri(kind, level, GL_TEXTURE_WIDTH) != size
                                || glGetTexLevelParameteri(kind, level, GL_TEXTURE_HEIGHT) != size
                                || glGetTexLevelParameteri(kind, level, GL_TEXTURE_DEPTH) != layers)
                            throw new IllegalStateException("Incorrect immutable mip geometry");
                        var data = stack.malloc(size * size * layers * 4);
                        for (int layer = 0; layer < layers; layer++)
                            for (int i = 0; i < size * size; i++)
                                data.put((byte) (20 + level * 40 + layer * 7))
                                        .put((byte) 83)
                                        .put((byte) 117)
                                        .put((byte) 255);
                        data.flip();
                        GL45.glTextureSubImage3D(
                                texture, level, 0, 0, 0, size, size, layers, GL_RGBA, GL_UNSIGNED_BYTE, data);
                    }
                    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
                    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
                    glViewport(0, 0, 2, 2);
                    glDisable(GL_BLEND);
                    glDisable(GL_DEPTH_TEST);
                    glDisable(GL_SCISSOR_TEST);
                    glUseProgram(program);
                    glUniform1i(glGetUniformLocation(program, "source"), 110);
                    for (int level = 0; level < 3; level++)
                        for (int layer = 0; layer < (array ? 4 : 4 >> level); layer++) {
                            glUniform1i(glGetUniformLocation(program, "level"), level);
                            glUniform1i(glGetUniformLocation(program, "layer"), layer);
                            glDrawArrays(GL_TRIANGLES, 0, 3);
                            var pixels = ResourceReadback.layer(target, 0);
                            for (int i = 0; i < 4; i++) {
                                int at = i * 4;
                                if ((pixels[at] & 255) != 20 + level * 40 + layer * 7
                                        || (pixels[at + 1] & 255) != 83
                                        || (pixels[at + 2] & 255) != 117
                                        || (pixels[at + 3] & 255) != 255)
                                    throw new IllegalStateException(
                                            "Bound-storage sample mismatch: level=" + level + " layer=" + layer);
                            }
                        }
                } finally {
                    glUseProgram(0);
                    glDeleteProgram(program);
                    glDeleteTextures(texture);
                    glDeleteTextures(other);
                }
            }
            Files.writeString(
                    output.resolve("bound-volume-storage.txt"),
                    "PASS\nverified_pixels=76\nchecks=bound immutable 3D/array storage,all mip levels/layers,geometry and immutable queries,invalid mip count,redefinition rejected,unit 110,independent target bindings\n");
        } finally {
            glActiveTexture(GL_TEXTURE0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteTextures(target);
            glDeleteFramebuffers(fbo);
        }
    }

    private static int program(boolean array) {
        int p = glCreateProgram(), v = glCreateShader(GL_VERTEX_SHADER), f = glCreateShader(GL_FRAGMENT_SHADER);
        try {
            glShaderSource(
                    v,
                    "#version 330 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0,1);}");
            glShaderSource(
                    f,
                    "#version 330 core\nuniform sampler" + (array ? "2DArray" : "3D")
                            + " source;uniform int layer;uniform int level;out vec4 color;void main(){ivec2 p=min(ivec2(gl_FragCoord.xy),textureSize(source,level).xy-1);color=texelFetch(source,ivec3(p,layer),level);}");
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
