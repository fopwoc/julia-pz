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
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryStack;

/** Float client data must retain its bits, unpack layout, and binding across queued uploads. */
final class FloatTextureChecks {
    private FloatTextureChecks() {}

    static void run(Path output) throws IOException {
        Files.deleteIfExists(output.resolve("float-textures.txt"));
        int target = GL45.glCreateTextures(GL_TEXTURE_2D), fbo = glGenFramebuffers(), program = program();
        GL45.glTextureStorage2D(target, 1, GL_R32UI, 3, 2);
        try (var stack = MemoryStack.stackPush()) {
            for (int channels : new int[] {1, 2, 4}) {
                int source = glGenTextures(), other = glGenTextures();
                try {
                    int format = channels == 1 ? GL_RED : channels == 2 ? GL_RG : GL_RGBA;
                    int storage = channels == 1 ? GL_R32F : channels == 2 ? GL_RG32F : GL_RGBA32F;
                    glActiveTexture(GL_TEXTURE3);
                    glBindTexture(GL_TEXTURE_2D, source);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                    glTexImage2D(GL_TEXTURE_2D, 0, storage, 3, 2, 0, format, GL_FLOAT, (ByteBuffer) null);
                    glBindTexture(GL_TEXTURE_3D, other);
                    var data = stack.callocFloat(2 + 12 * channels);
                    data.put(0, -999f).put(data.capacity() - 1, -999f);
                    float[] expected = new float[6 * channels];
                    for (int y = 0; y < 2; y++)
                        for (int x = 0; x < 3; x++)
                            for (int c = 0; c < channels; c++) {
                                float value = (y * 3 + x) * 1.125f - c * 0.03125f - 2.5f;
                                expected[(y * 3 + x) * channels + c] = value;
                                data.put(1 + ((y + 1) * 4 + x + 1) * channels + c, value);
                            }
                    data.position(1).limit(data.capacity() - 1);
                    glPixelStorei(GL_UNPACK_ALIGNMENT, 8);
                    glPixelStorei(GL_UNPACK_ROW_LENGTH, 4);
                    glPixelStorei(GL_UNPACK_SKIP_ROWS, 1);
                    glPixelStorei(GL_UNPACK_SKIP_PIXELS, 1);
                    Consumer<FloatBuffer> upload =
                            b -> glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 3, 2, format, GL_FLOAT, b);
                    try {
                        upload.accept(stack.callocFloat(2));
                        throw new IllegalStateException("Float upload read beyond client data");
                    } catch (IllegalArgumentException expectedFailure) {
                    }
                    upload.accept(data);
                    if (data.position() != 1
                            || data.limit() != data.capacity() - 1
                            || data.get(0) != -999f
                            || data.duplicate().clear().get(data.capacity() - 1) != -999f)
                        throw new IllegalStateException("Float upload changed client position/limit/canaries");
                    data.put(1 + 5 * channels, 99f);
                    if (glGetInteger(GL_ACTIVE_TEXTURE) != GL_TEXTURE3
                            || glGetInteger(GL_TEXTURE_BINDING_2D) != source
                            || glGetInteger(GL_TEXTURE_BINDING_3D) != other)
                        throw new IllegalStateException("Float upload changed bindings");
                    resetUnpack();
                    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
                    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
                    glViewport(0, 0, 3, 2);
                    glDisable(GL_BLEND);
                    glDisable(GL_DEPTH_TEST);
                    glDisable(GL_SCISSOR_TEST);
                    glUseProgram(program);
                    glActiveTexture(GL_TEXTURE0);
                    glBindTexture(GL_TEXTURE_2D, source);
                    glUniform1i(glGetUniformLocation(program, "source"), 0);
                    verify(target, program, expected, channels);
                    glActiveTexture(GL_TEXTURE3);
                    glPixelStorei(GL_UNPACK_SWAP_BYTES, GL_TRUE);
                    var swapped = stack.callocFloat(channels);
                    for (int c = 0; c < channels; c++) {
                        expected[5 * channels + c] = 23.125f + c;
                        swapped.put(
                                c,
                                Float.intBitsToFloat(
                                        Integer.reverseBytes(Float.floatToRawIntBits(expected[5 * channels + c]))));
                    }
                    glTexSubImage2D(GL_TEXTURE_2D, 0, 2, 1, 1, 1, format, GL_FLOAT, swapped);
                    resetUnpack();
                    if (swapped.position() != 0) throw new IllegalStateException("Float upload consumed buffer");
                    glActiveTexture(GL_TEXTURE0);
                    verify(target, program, expected, channels);
                } finally {
                    resetUnpack();
                    glActiveTexture(GL_TEXTURE0);
                    glDeleteTextures(source);
                    glDeleteTextures(other);
                }
            }
            Files.writeString(
                    output.resolve("float-textures.txt"),
                    "PASS\nverified_pixels=84\nchecks=R32F/RG32F/RGBA32F exact bits,unpack strides/skips,client bounds,position/limit/canaries,immediate reuse,logical bindings,byte swap,subregions\n");
        } finally {
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(fbo);
            glDeleteTextures(target);
            glDeleteProgram(program);
        }
    }

    private static void resetUnpack() {
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
        glPixelStorei(GL_UNPACK_SWAP_BYTES, GL_FALSE);
    }

    private static void verify(int target, int program, float[] expected, int channels) {
        for (int c = 0; c < channels; c++) {
            glUniform1i(glGetUniformLocation(program, "channel"), c);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            var values = ByteBuffer.wrap(ResourceReadback.layer(target, 0))
                    .order(ByteOrder.nativeOrder())
                    .asIntBuffer();
            for (int i = 0; i < 6; i++)
                if (values.get(i) != Float.floatToRawIntBits(expected[i * channels + c]))
                    throw new IllegalStateException("Float texture mismatch at " + i + " channel " + c);
        }
    }

    private static int program() {
        int p = glCreateProgram(), v = glCreateShader(GL_VERTEX_SHADER), f = glCreateShader(GL_FRAGMENT_SHADER);
        try {
            glShaderSource(
                    v,
                    "#version 330 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0,1);}");
            glShaderSource(
                    f,
                    "#version 330 core\nuniform sampler2D source;uniform int channel;layout(location=0)out uint value;void main(){value=floatBitsToUint(texelFetch(source,ivec2(gl_FragCoord.xy),0)[channel]);}");
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
