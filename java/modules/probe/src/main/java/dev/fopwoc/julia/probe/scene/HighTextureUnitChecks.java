package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL20.glUniform1iv;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.*;
import static org.lwjgl.opengl.GL31.glTexBuffer;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL33.glDeleteSamplers;
import static org.lwjgl.opengl.GL33.glGenSamplers;
import static org.lwjgl.opengl.GL33.glSamplerParameteri;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryStack;

/** Logical units above 31 must stay independent of native sampler slots and each other. */
final class HighTextureUnitChecks {
    private HighTextureUnitChecks() {}

    static void run(Path output) throws IOException {
        Files.deleteIfExists(output.resolve("high-texture-units.txt"));
        if (glGetInteger(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS) != 128 || glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS) != 32)
            throw new IllegalStateException("Logical and physical sampler limits are not separate");
        int[] textures = new int[5], buffers = new int[2];
        int target = GL45.glCreateTextures(GL_TEXTURE_2D), fbo = glGenFramebuffers();
        int direct = program(false, false),
                indirect = program(true, false),
                sampler = glGenSamplers(),
                fetch = program(true, true);
        GL45.glTextureStorage2D(target, 1, GL_R32UI, 2, 1);
        try (var stack = MemoryStack.stackPush()) {
            int[] units = {14, 96, 127, 78, 110};
            for (int i = 0; i < 5; i++) {
                glActiveTexture(GL_TEXTURE0 + units[i]);
                if (glGetInteger(GL_ACTIVE_TEXTURE) != GL_TEXTURE0 + units[i])
                    throw new IllegalStateException("GLSM rejected a valid high texture unit");
                textures[i] = glGenTextures();
                if (i < 3) {
                    glBindTexture(GL_TEXTURE_2D, textures[i]);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
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
                                    (byte) (i * 40 + 20),
                                    (byte) 0,
                                    (byte) 0,
                                    (byte) 255,
                                    (byte) (i * 40 + 40),
                                    (byte) 0,
                                    (byte) 0,
                                    (byte) 255));
                    if (glGetInteger(GL_TEXTURE_BINDING_2D) != textures[i])
                        throw new IllegalStateException("High binding query lost texture");
                } else {
                    buffers[i - 3] = glGenBuffers();
                    glBindBuffer(GL_TEXTURE_BUFFER, buffers[i - 3]);
                    glBufferData(GL_TEXTURE_BUFFER, stack.ints(i == 3 ? 101 : 202), GL_STATIC_DRAW);
                    glBindTexture(GL_TEXTURE_BUFFER, textures[i]);
                    glTexBuffer(GL_TEXTURE_BUFFER, GL_R32UI, buffers[i - 3]);
                    if (glGetInteger(GL_TEXTURE_BINDING_BUFFER) != textures[i])
                        throw new IllegalStateException("High buffer binding lost texture");
                }
            }
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            glViewport(0, 0, 2, 1);
            glDisable(GL_BLEND);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_SCISSOR_TEST);
            glUseProgram(direct);
            try {
                glDrawArrays(GL_TRIANGLES, 0, 3);
                throw new AssertionError("Default-unit sampler conflict accepted");
            } catch (IllegalStateException expected) {
                String message = expected.getMessage();
                if (!message.contains("Different sampler targets share texture unit 0")
                        || !message.contains("first=0 (default)")
                        || !message.contains("image=0 (default)")
                        || !message.contains("second=0 (default)")
                        || message.contains("unused")) throw expected;
            }
            glUniform1i(glGetUniformLocation(direct, "image"), 14);
            glUniform1i(glGetUniformLocation(direct, "first"), 78);
            glUniform1i(glGetUniformLocation(direct, "second"), 110);
            for (String name :
                    new String[] {"unusedCube", "unusedBuffer", "unusedLayers", "unusedLayers[0]", "unusedLayers[1]"})
                if (glGetUniformLocation(direct, name) != -1)
                    throw new IllegalStateException("Unreachable sampler became an active uniform: " + name);
            glUniform1i(glGetUniformLocation(direct, "first"), 14);
            try {
                glDrawArrays(GL_TRIANGLES, 0, 3);
                throw new AssertionError("Conflicting active sampler targets accepted");
            } catch (IllegalStateException expected) {
                String message = expected.getMessage();
                if (!message.contains("Different sampler targets share texture unit 14")
                        || !message.contains("first (GL_TEXTURE_BUFFER)")
                        || !message.contains("image (GL_TEXTURE_2D)")
                        || !message.contains("first=14")
                        || !message.contains("second=110")
                        || message.contains("unused")) throw expected;
            }
            glUniform1i(glGetUniformLocation(direct, "first"), 78);
            verify(target, 323); // Units 14, 78 and 110 all alias in a 32-bit unit mask.
            glUseProgram(indirect);
            int images = glGetUniformLocation(indirect, "images");
            var assignments = stack.ints(-1, 96, 127, -1);
            assignments.position(1).limit(3);
            glUniform1iv(images, assignments);
            if (assignments.position() != 1 || assignments.limit() != 3)
                throw new IllegalStateException("Unit upload consumed buffer");
            glUniform1i(glGetUniformLocation(indirect, "first"), 78);
            glUniform1i(glGetUniformLocation(indirect, "second"), 110);
            verify(target, 483);
            try {
                glUniform1iv(images, new int[] {14, 128});
                throw new IllegalStateException("Out-of-range logical sampler unit accepted");
            } catch (IllegalArgumentException expected) {
            }
            verify(target, 483); // An invalid bulk assignment must leave every element unchanged.
            glActiveTexture(GL_TEXTURE0 + 127);
            glBindTexture(GL_TEXTURE_2D, textures[1]);
            glSamplerParameteri(sampler, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glSamplerParameteri(sampler, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glBindSampler(127, sampler);
            verify(target, 433); // Unit 96 samples red 60; unit 127 interpolates red 60 and 80 to 70.
            glBindSampler(127, 0);
            verify(target, 443);
            glBindTexture(GL_TEXTURE_2D, 0);
            glActiveTexture(GL_TEXTURE0 + 127);
            if (glGetInteger(GL_TEXTURE_BINDING_2D) != 0) throw new IllegalStateException("High unit did not unbind");
            glBindTexture(GL_TEXTURE_2D, textures[2]);
            glUseProgram(fetch);
            glUniform1iv(glGetUniformLocation(fetch, "images"), new int[] {96, 127});
            glUniform1i(glGetUniformLocation(fetch, "first"), 78);
            glUniform1i(glGetUniformLocation(fetch, "second"), 110);
            verify(target, 483);
            glDeleteTextures(textures[2]);
            textures[2] = 0;
            if (glGetInteger(GL_TEXTURE_BINDING_2D) != 0)
                throw new IllegalStateException("Deletion retained high binding");
            Files.writeString(
                    output.resolve("high-texture-units.txt"),
                    "PASS\nverified_pixels=12\nchecks=unreachable sampler helpers,active target conflicts,128 logical units,32 graphics samplers,units 14/78/96/110/127,direct/argument bindings,buffer-mask aliasing,array uploads,atomic validation,sampler overrides,samplerless argument arrays,binding/unbinding,deletion\n");
        } finally {
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glActiveTexture(GL_TEXTURE0);
            for (int texture : textures) if (texture != 0) glDeleteTextures(texture);
            for (int buffer : buffers) if (buffer != 0) glDeleteBuffers(buffer);
            glDeleteSamplers(sampler);
            glDeleteTextures(target);
            glDeleteFramebuffers(fbo);
            glDeleteProgram(direct);
            glDeleteProgram(indirect);
            glDeleteProgram(fetch);
        }
    }

    private static void verify(int target, int expected) {
        glDrawArrays(GL_TRIANGLES, 0, 3);
        var values = ByteBuffer.wrap(ResourceReadback.layer(target, 0))
                .order(ByteOrder.nativeOrder())
                .asIntBuffer();
        for (int i = 0; i < 2; i++)
            if (values.get(i) != expected)
                throw new IllegalStateException("High-unit sample " + values.get(i) + " != " + expected);
    }

    private static int program(boolean array, boolean fetch) {
        int p = glCreateProgram(), v = glCreateShader(GL_VERTEX_SHADER), f = glCreateShader(GL_FRAGMENT_SHADER);
        try {
            glShaderSource(
                    v,
                    "#version 330 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0,1);}");
            glShaderSource(
                    f,
                    "#version 330 core\nuniform sampler2D " + (array ? "images[2]" : "image")
                            + ";uniform samplerCube unusedCube;uniform usamplerBuffer unusedBuffer;uniform sampler2DArray unusedLayers[2];uniform usamplerBuffer first;uniform usamplerBuffer second;layout(location=0)out uint value;vec4 unusedSample(){return texture(unusedCube,vec3(1))+vec4(texelFetch(unusedBuffer,0))+texture(unusedLayers[1],vec3(0));}void main(){value=texelFetch(first,0).r+texelFetch(second,0).r+"
                            + (array
                                    ? fetch
                                            ? "uint(round(texelFetch(images[0],ivec2(0),0).r*255.))+uint(round(texelFetch(images[1],ivec2(1,0),0).r*255.))"
                                            : "uint(round(texture(images[0],vec2(0.25,0.5)).r*255.))+uint(round(texture(images[1],vec2(0.5)).r*255.))"
                                    : "uint(round(texture(image,vec2(0.25,0.5)).r*255.))")
                            + ";}");
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
