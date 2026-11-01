package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryStack;

/** RGB32 integer volumes preserve raw bits, implicit alpha, and untouched layers through typed uploads. */
final class IntegerVolumeChecks {
    private IntegerVolumeChecks() {}

    static void run(Path output) throws IOException {
        Files.deleteIfExists(output.resolve("integer-volumes.txt"));
        int target = GL45.glCreateTextures(GL_TEXTURE_2D), fbo = glGenFramebuffers();
        GL45.glTextureStorage2D(target, 1, GL_R32UI, 3, 2);
        try (var stack = MemoryStack.stackPush()) {
            for (boolean array : new boolean[] {false, true})
                for (boolean signed : new boolean[] {false, true}) {
                    int kind = array ? GL_TEXTURE_2D_ARRAY : GL_TEXTURE_3D;
                    int storage = signed ? GL_RGB32I : GL_RGB32UI, type = signed ? GL_INT : GL_UNSIGNED_INT;
                    int source = glGenTextures(), other = glGenTextures(), program = program(array, signed);
                    try {
                        glActiveTexture(GL_TEXTURE3);
                        glBindTexture(kind, source);
                        glTexParameteri(kind, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                        glTexParameteri(kind, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                        var initial = stack.callocInt(54);
                        int[] expected = new int[72];
                        for (int i = 0; i < 18; i++) {
                            for (int c = 0; c < 3; c++) {
                                int value = 0x80000000 | (i + 1) * 257 + c * 0x100000;
                                initial.put(i * 3 + c, value);
                                expected[i * 4 + c] = value;
                            }
                            expected[i * 4 + 3] = 1;
                        }
                        if (array != signed) {
                            glTexImage3D(kind, 0, storage, 3, 2, 3, 0, GL_RGB_INTEGER, type, initial);
                        } else {
                            GL45.glTextureStorage3D(source, 1, storage, 3, 2, 3);
                            GL45.glTextureSubImage3D(source, 0, 0, 0, 0, 3, 2, 3, GL_RGB_INTEGER, type, initial);
                        }
                        glBindTexture(GL_TEXTURE_2D, other);
                        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
                        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
                        glViewport(0, 0, 3, 2);
                        glDisable(GL_BLEND);
                        glDisable(GL_DEPTH_TEST);
                        glDisable(GL_SCISSOR_TEST);
                        glUseProgram(program);
                        glActiveTexture(GL_TEXTURE0);
                        glBindTexture(kind, source);
                        glUniform1i(glGetUniformLocation(program, "source"), 0);
                        verify(target, program, expected);
                        glActiveTexture(GL_TEXTURE3);
                        glPixelStorei(GL_UNPACK_ALIGNMENT, 8);
                        glPixelStorei(GL_UNPACK_ROW_LENGTH, 4);
                        glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, 3);
                        glPixelStorei(GL_UNPACK_SKIP_IMAGES, 1);
                        glPixelStorei(GL_UNPACK_SKIP_ROWS, 1);
                        glPixelStorei(GL_UNPACK_SKIP_PIXELS, 1);
                        var data = stack.callocInt(110);
                        data.put(0, 0x76543210).put(109, 0x76543210);
                        for (int z = 0; z < 2; z++)
                            for (int x = 0; x < 2; x++)
                                for (int c = 0; c < 3; c++) {
                                    int value = 0xfedc0000 | z * 4096 + x * 256 + c;
                                    data.put(1 + 51 + z * 36 + x * 3 + c, value);
                                    expected[((z + 1) * 6 + 3 + x + 1) * 4 + c] = value;
                                }
                        data.position(1).limit(109);
                        Consumer<IntBuffer> upload =
                                b -> glTexSubImage3D(kind, 0, 1, 1, 1, 2, 1, 2, GL_RGB_INTEGER, type, b);
                        try {
                            upload.accept(stack.callocInt(2));
                            throw new IllegalStateException("Integer volume upload exceeded client bounds");
                        } catch (IllegalArgumentException expectedFailure) {
                        }
                        upload.accept(data);
                        if (data.position() != 1
                                || data.limit() != 109
                                || data.get(0) != 0x76543210
                                || data.duplicate().clear().get(109) != 0x76543210)
                            throw new IllegalStateException("Integer volume upload changed buffer state/canaries");
                        data.put(52, 0); // Queued transfers must already own their client bytes.
                        int binding = array ? GL_TEXTURE_BINDING_2D_ARRAY : GL_TEXTURE_BINDING_3D;
                        if (glGetInteger(GL_ACTIVE_TEXTURE) != GL_TEXTURE3
                                || glGetInteger(binding) != source
                                || glGetInteger(GL_TEXTURE_BINDING_2D) != other)
                            throw new IllegalStateException("Integer volume upload changed bindings");
                        resetUnpack();
                        glActiveTexture(GL_TEXTURE0);
                        verify(target, program, expected);
                        glActiveTexture(GL_TEXTURE3);
                        glPixelStorei(GL_UNPACK_SWAP_BYTES, GL_TRUE);
                        var swapped = stack.ints(
                                Integer.reverseBytes(0x12345678),
                                Integer.reverseBytes(0x87654321),
                                Integer.reverseBytes(-1));
                        glTexSubImage3D(kind, 0, 0, 0, 0, 1, 1, 1, GL_RGB_INTEGER, type, swapped);
                        if (swapped.position() != 0)
                            throw new IllegalStateException("Integer volume upload consumed buffer");
                        expected[0] = 0x12345678;
                        expected[1] = 0x87654321;
                        expected[2] = -1;
                        resetUnpack();
                        glActiveTexture(GL_TEXTURE0);
                        verify(target, program, expected);
                    } finally {
                        resetUnpack();
                        glUseProgram(0);
                        glActiveTexture(GL_TEXTURE0);
                        glDeleteTextures(source);
                        glDeleteTextures(other);
                        glDeleteProgram(program);
                    }
                }
            Files.writeString(
                    output.resolve("integer-volumes.txt"),
                    "PASS\nverified_pixels=864\nchecks=RGB32I/UI initial and subimage uploads,mutable/named storage,3D/array textures,exact bits,alpha one,padded rows/images/skips,untouched layers,buffer bounds/canaries/position,immediate reuse,logical bindings,byte swap\n");
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(fbo);
            glDeleteTextures(target);
        }
    }

    private static void resetUnpack() {
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, 0);
        glPixelStorei(GL_UNPACK_SKIP_IMAGES, 0);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
        glPixelStorei(GL_UNPACK_SWAP_BYTES, GL_FALSE);
    }

    private static void verify(int target, int program, int[] expected) {
        for (int z = 0; z < 3; z++)
            for (int c = 0; c < 4; c++) {
                glUniform1i(glGetUniformLocation(program, "layer"), z);
                glUniform1i(glGetUniformLocation(program, "channel"), c);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                var values = ByteBuffer.wrap(ResourceReadback.layer(target, 0))
                        .order(ByteOrder.nativeOrder())
                        .asIntBuffer();
                for (int i = 0; i < 6; i++)
                    if (values.get(i) != expected[(z * 6 + i) * 4 + c])
                        throw new IllegalStateException("Integer volume mismatch at layer " + z + " pixel " + i
                                + " channel " + c + ": " + Integer.toHexString(values.get(i)) + " != "
                                + Integer.toHexString(expected[(z * 6 + i) * 4 + c]));
            }
    }

    private static int program(boolean array, boolean signed) {
        int p = glCreateProgram(), v = glCreateShader(GL_VERTEX_SHADER), f = glCreateShader(GL_FRAGMENT_SHADER);
        try {
            glShaderSource(
                    v,
                    "#version 330 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0,1);}");
            glShaderSource(
                    f,
                    "#version 330 core\nuniform " + (signed ? "i" : "u") + "sampler" + (array ? "2DArray" : "3D")
                            + " source;uniform int layer;uniform int channel;layout(location=0)out uint value;void main(){value=uvec4(texelFetch(source,ivec3(ivec2(gl_FragCoord.xy),layer),0))[channel];}");
            for (int s : new int[] {v, f}) {
                glCompileShader(s);
                if (glGetShaderi(s, GL_COMPILE_STATUS) == 0) throw new IllegalStateException(glGetShaderInfoLog(s));
                glAttachShader(p, s);
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
