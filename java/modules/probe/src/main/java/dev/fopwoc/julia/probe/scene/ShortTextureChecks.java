package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_3D;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryStack;

/** R16 must retain all 65,536 height values across typed stripe uploads, not quantize to RGBA8. */
final class ShortTextureChecks {
    private ShortTextureChecks() {}

    static void run(Path output) throws IOException {
        Files.deleteIfExists(output.resolve("short-textures.txt"));
        int height = glGenTextures(), other = glGenTextures(), target = GL45.glCreateTextures(GL_TEXTURE_2D);
        int fbo = glGenFramebuffers(), program = program();
        try (var stack = MemoryStack.stackPush()) {
            glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
            glActiveTexture(GL_TEXTURE3);
            glBindTexture(GL_TEXTURE_2D, height);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_R16, 3, 4, 0, GL_RED, GL_UNSIGNED_SHORT, (ByteBuffer) null);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 3, 4, GL_RED, GL_UNSIGNED_SHORT, stack.callocShort(16));
            glBindTexture(GL_TEXTURE_3D, other);
            var stripe = stack.shorts(
                    (short) -99,
                    (short) 1,
                    (short) 257,
                    (short) 32768,
                    (short) 9922,
                    (short) 65534,
                    (short) 65535,
                    (short) 12345,
                    (short) -99);
            stripe.position(1).limit(8);
            Consumer<ShortBuffer> upload =
                    data -> glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 1, 3, 2, GL_RED, GL_UNSIGNED_SHORT, data);
            try {
                upload.accept(stack.shorts((short) 1, (short) 2, (short) 3));
                throw new IllegalStateException("Short upload read beyond client data");
            } catch (IllegalArgumentException expected) {
            }
            upload.accept(stripe);
            if (stripe.position() != 1
                    || stripe.limit() != 8
                    || stripe.get(0) != (short) -99
                    || stripe.duplicate().clear().get(8) != (short) -99)
                throw new IllegalStateException("Short upload changed client position/limit/canaries");
            stripe.put(1, (short) 0); // Client memory may be reused immediately after the GL upload call.
            if (glGetInteger(GL_ACTIVE_TEXTURE) != GL_TEXTURE3
                    || glGetInteger(GL_TEXTURE_BINDING_2D) != height
                    || glGetInteger(GL_TEXTURE_BINDING_3D) != other)
                throw new IllegalStateException("Typed upload changed active unit or target bindings");
            GL45.glTextureStorage2D(target, 1, GL_R32UI, 3, 4);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            glViewport(0, 0, 3, 4);
            glDisable(GL_BLEND);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_SCISSOR_TEST);
            glUseProgram(program);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, height);
            glUniform1i(glGetUniformLocation(program, "source"), 0);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            int[] expected = {0, 0, 0, 1, 257, 32768, 65534, 65535, 12345, 0, 0, 0};
            verify(target, expected);
            glActiveTexture(GL_TEXTURE3);
            glPixelStorei(GL_UNPACK_SWAP_BYTES, GL_TRUE);
            var swapped = stack.shorts(Short.reverseBytes((short) 4660));
            glTexSubImage2D(GL_TEXTURE_2D, 0, 2, 3, 1, 1, GL_RED, GL_UNSIGNED_SHORT, swapped);
            glPixelStorei(GL_UNPACK_SWAP_BYTES, GL_FALSE);
            if (swapped.position() != 0)
                throw new IllegalStateException("Byte-swapped short upload consumed its buffer");
            glActiveTexture(GL_TEXTURE0);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            expected[11] = 4660;
            verify(target, expected);
            Files.writeString(
                    output.resolve("short-textures.txt"),
                    "PASS\nverified_pixels=24\nchecks=mutable R16 precision,typed stripes,odd-width row padding,short client bounds,position/limit/canaries,immediate client reuse,independent bindings,byte swap\n");
        } finally {
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glPixelStorei(GL_UNPACK_SWAP_BYTES, GL_FALSE);
            glActiveTexture(GL_TEXTURE0);
            glDeleteFramebuffers(fbo);
            glDeleteTextures(height);
            glDeleteTextures(other);
            glDeleteTextures(target);
            glDeleteProgram(program);
        }
    }

    private static void verify(int texture, int[] expected) {
        var values = ByteBuffer.wrap(ResourceReadback.layer(texture, 0))
                .order(ByteOrder.nativeOrder())
                .asIntBuffer();
        for (int i = 0; i < expected.length; i++)
            if (values.get(i) != expected[i])
                throw new IllegalStateException(
                        "R16 height mismatch at " + i + ": " + values.get(i) + " != " + expected[i]);
    }

    private static int program() {
        int program = glCreateProgram(),
                vertex = glCreateShader(GL_VERTEX_SHADER),
                fragment = glCreateShader(GL_FRAGMENT_SHADER);
        try {
            glShaderSource(
                    vertex,
                    "#version 330 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0,1);}");
            glShaderSource(
                    fragment,
                    "#version 330 core\nuniform sampler2D source;layout(location=0)out uint value;void main(){value=uint(round(texelFetch(source,ivec2(gl_FragCoord.xy),0).r*65535.));}");
            for (int shader : new int[] {vertex, fragment}) {
                glCompileShader(shader);
                if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0)
                    throw new IllegalStateException(glGetShaderInfoLog(shader));
                glAttachShader(program, shader);
            }
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == 0)
                throw new IllegalStateException(glGetProgramInfoLog(program));
            return program;
        } finally {
            glDeleteShader(vertex);
            glDeleteShader(fragment);
        }
    }
}
