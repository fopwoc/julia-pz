package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL33.GL_TEXTURE_SWIZZLE_R;
import static org.lwjgl.opengl.GL42.GL_TEXTURE_IMMUTABLE_FORMAT;
import static org.lwjgl.opengl.GL43.GL_TEXTURE_IMMUTABLE_LEVELS;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.IntUnaryOperator;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryUtil;

/** Real named-object uploads exercise binding independence, padding, mips, arrays, volumes and typed texels. */
final class NamedTextureChecks {
    private static int verified;

    private NamedTextureChecks() {}

    static void run(Path output) throws IOException {
        verified = 0;
        Files.deleteIfExists(output.resolve("named-textures.txt"));
        IntUnaryOperator create = GL45::glCreateTextures;
        int original = glGenTextures(), target = glGenTextures(), fbo = glGenFramebuffers();
        int texture = create.applyAsInt(GL_TEXTURE_2D);
        int array = create.applyAsInt(GL_TEXTURE_2D_ARRAY);
        int volume = create.applyAsInt(GL_TEXTURE_3D);
        int floats = create.applyAsInt(GL_TEXTURE_2D);
        var bytes = MemoryUtil.memAlloc(512);
        var ints = MemoryUtil.memAllocInt(66);
        var values = MemoryUtil.memAllocFloat(66);
        try {
            glActiveTexture(GL_TEXTURE3);
            glBindTexture(GL_TEXTURE_2D, original);
            if (!glIsTexture(texture))
                throw new IllegalStateException("Created texture is not an object before storage");
            GL45.glTextureParameteri(texture, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            GL45.glTextureParameteri(texture, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            GL45.glTextureParameteri(texture, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            GL45.glTextureStorage2D(texture, 3, GL_RGBA8, 8, 8);
            int packed = java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN ? 0xff996633 : 0x336699ff;
            for (int i = 0; i < ints.capacity(); i++) ints.put(i, packed);
            ints.position(1).limit(65);
            GL45.glTextureSubImage2D(texture, 0, 0, 0, 8, 8, GL_RGBA, GL_UNSIGNED_BYTE, ints);
            if (ints.position() != 1 || ints.limit() != 65)
                throw new IllegalStateException("Named upload consumed client buffer");
            unchanged(original);
            verify(ResourceReadback.layer(texture, 0), 8, 8, 8, 51, 102, 153);
            // Padded rows, skipped pixels/rows, and a nonzero client buffer position.
            glPixelStorei(GL_UNPACK_ALIGNMENT, 8);
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 5);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, 1);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, 2);
            bytes.position(7).limit(bytes.capacity());
            for (int row = 0; row < 2; row++)
                for (int x = 0; x < 2; x++) {
                    int offset = 7 + 24 + 8 + row * 24 + x * 4;
                    bytes.put(offset, (byte) 204)
                            .put(offset + 1, (byte) 51)
                            .put(offset + 2, (byte) 102)
                            .put(offset + 3, (byte) 255);
                }
            GL45.glTextureSubImage2D(texture, 0, 3, 2, 2, 2, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
            var pixels = ResourceReadback.layer(texture, 0);
            for (int y = 0; y < 8; y++)
                for (int x = 0; x < 8; x++) {
                    boolean patch = x >= 3 && x < 5 && y >= 2 && y < 4;
                    pixel(pixels, (y * 8 + x) * 4, patch ? 204 : 51, patch ? 51 : 102, patch ? 102 : 153);
                }
            resetUnpack();
            unchanged(original);
            try {
                GL45.glTextureStorage2D(texture, 1, GL_RGBA8, 8, 8);
                throw new IllegalStateException("Immutable storage redefinition accepted");
            } catch (IllegalStateException expected) {
                if (!expected.getMessage().contains("Cannot redefine")) throw expected;
            }
            try {
                GL45.glTextureSubImage2D(texture, 0, 7, 7, 2, 2, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
                throw new IllegalStateException("Out-of-bounds upload accepted");
            } catch (IllegalArgumentException expected) {
                unchanged(original);
            }
            // All array layers are initialized; mip one retains all three layers.
            GL45.glTextureStorage3D(array, 2, GL_RGBA8, 4, 4, 3);
            bytes.clear();
            for (int layer = 0; layer < 3; layer++)
                for (int i = 0; i < 16; i++) color(bytes, layer * 64 + i * 4, (layer + 1) * 51, 102, 153);
            GL45.glTextureSubImage3D(array, 0, 0, 0, 0, 4, 4, 3, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
            // Explicit image height and skipped image, then a two-layer mip upload.
            glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, 3);
            glPixelStorei(GL_UNPACK_SKIP_IMAGES, 1);
            for (int layer = 0; layer < 2; layer++)
                for (int row = 0; row < 2; row++)
                    for (int x = 0; x < 2; x++)
                        color(bytes, 24 + (layer * 3 + row) * 8 + x * 4, 204, 51 + layer * 51, 102);
            GL45.glTextureSubImage3D(array, 1, 0, 0, 1, 2, 2, 2, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
            resetUnpack();
            unchanged(original);
            GL45.glTextureStorage3D(volume, 2, GL_RGBA8, 4, 4, 4);
            for (int layer = 0; layer < 4; layer++)
                for (int i = 0; i < 16; i++) color(bytes, layer * 64 + i * 4, 51, (layer + 1) * 51, 153);
            GL45.glTextureSubImage3D(volume, 0, 0, 0, 0, 4, 4, 4, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
            for (int i = 0; i < 8; i++) color(bytes, i * 4, 153, 102, 51);
            GL45.glTextureSubImage3D(volume, 1, 0, 0, 0, 2, 2, 2, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
            GL45.glTextureStorage2D(floats, 1, GL_RGBA32F, 4, 4);
            for (int i = 0; i < 16; i++) {
                values.put(1 + i * 4, 0.2f);
                values.put(2 + i * 4, 0.4f);
                values.put(3 + i * 4, 0.6f);
                values.put(4 + i * 4, 1);
            }
            values.position(1).limit(65);
            GL45.glTextureSubImage2D(floats, 0, 0, 0, 4, 4, GL_RGBA, GL_FLOAT, values);
            if (values.position() != 1) throw new IllegalStateException("Float upload consumed client buffer");
            unchanged(original);
            glBindTexture(GL_TEXTURE_2D, target);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 8, 8, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            glDisable(GL_BLEND);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_SCISSOR_TEST);
            glDisable(GL_CULL_FACE);
            int program = program("sampler2DArray", "ivec3(ivec2(gl_FragCoord.xy),slice)");
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D_ARRAY, array);
            for (int layer = 0; layer < 3; layer++) sample(program, target, 0, layer, 4, (layer + 1) * 51, 102, 153);
            for (int layer = 1; layer < 3; layer++) sample(program, target, 1, layer, 2, 204, layer * 51, 102);
            glUseProgram(0);
            glDeleteProgram(program);
            program = program("sampler3D", "ivec3(ivec2(gl_FragCoord.xy),slice)");
            glBindTexture(GL_TEXTURE_3D, volume);
            for (int layer = 0; layer < 4; layer++) sample(program, target, 0, layer, 4, 51, (layer + 1) * 51, 153);
            for (int layer = 0; layer < 2; layer++) sample(program, target, 1, layer, 2, 153, 102, 51);
            glUseProgram(0);
            glDeleteProgram(program);
            program = program("sampler2D", "ivec2(gl_FragCoord.xy)");
            glBindTexture(GL_TEXTURE_2D, floats);
            sample(program, target, 0, 0, 4, 51, 102, 153);
            // A queued draw must keep the old texels when a named upload changes its source.
            glViewport(0, 0, 4, 4);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            values.position(1);
            for (int i = 0; i < 16; i++) values.put(1 + i * 4, 0.8f);
            GL45.glTextureSubImage2D(floats, 0, 0, 0, 4, 4, GL_RGBA, GL_FLOAT, values);
            verify(ResourceReadback.layer(target, 0), 8, 4, 4, 51, 102, 153);
            sample(program, target, 0, 0, 4, 204, 102, 153);
            glUseProgram(0);
            glDeleteProgram(program);
            typedFormats(target);
            glActiveTexture(GL_TEXTURE3);
            glBindTexture(GL_TEXTURE_2D, texture);
            if (glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_IMMUTABLE_FORMAT) != GL_TRUE
                    || glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_IMMUTABLE_LEVELS) != 3)
                throw new IllegalStateException("Named storage missing immutable state");
            if (glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER) != GL_NEAREST)
                throw new IllegalStateException("Named parameter lost");
            Files.writeString(
                    output.resolve("named-textures.txt"),
                    "PASS\nverified_pixels=" + verified
                            + "\nchecks=object identity,independent bindings,immutable storage,byte/int/float uploads,padded rows,skipped rows/pixels/images,array layers,volume slices,mips,draw ordering,invalid state\n");
        } finally {
            resetUnpack();
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, 0);
            glActiveTexture(GL_TEXTURE3);
            glBindTexture(GL_TEXTURE_2D, 0);
            glActiveTexture(GL_TEXTURE0);
            for (int name : new int[] {original, target, texture, array, volume, floats}) glDeleteTextures(name);
            glDeleteFramebuffers(fbo);
            MemoryUtil.memFree(bytes);
            MemoryUtil.memFree(ints);
            MemoryUtil.memFree(values);
        }
    }

    private static void typedFormats(int target) {
        int[] names = new int[5];
        GL45.glCreateTextures(GL_TEXTURE_2D, names);
        var words = MemoryUtil.memAllocInt(64);
        var half = MemoryUtil.memAllocShort(64);
        var bytes = MemoryUtil.memAlloc(64);
        try {
            for (int kind = 0; kind < 2; kind++) {
                GL45.glTextureStorage2D(names[kind], 1, kind == 0 ? GL_RGBA32UI : GL_RGBA32I, 4, 4);
                for (int i = 0; i < 16; i++) {
                    words.put(i * 4, kind == 0 ? 51 : -51);
                    words.put(i * 4 + 1, kind == 0 ? 102 : -102);
                    words.put(i * 4 + 2, kind == 0 ? 153 : -153);
                    words.put(i * 4 + 3, kind == 0 ? 255 : -255);
                }
                GL45.glTextureSubImage2D(
                        names[kind], 0, 0, 0, 4, 4, GL_RGBA_INTEGER, kind == 0 ? GL_UNSIGNED_INT : GL_INT, words);
                int program = program(kind == 0 ? "usampler2D" : "isampler2D", "ivec2(gl_FragCoord.xy)");
                glBindTexture(GL_TEXTURE_2D, names[kind]);
                sample(program, target, 0, 0, 4, 51, 102, 153);
                glUseProgram(0);
                glDeleteProgram(program);
            }
            GL45.glTextureStorage2D(names[2], 1, GL_R8, 3, 3);
            for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) bytes.put(y * 4 + x, (byte) 51);
            bytes.limit(11);
            GL45.glTextureSubImage2D(names[2], 0, 0, 0, 3, 3, GL_RED, GL_UNSIGNED_BYTE, bytes);
            int program = program("sampler2D", "ivec2(gl_FragCoord.xy)");
            glBindTexture(GL_TEXTURE_2D, names[2]);
            sample(program, target, 0, 0, 3, 51, 0, 0);
            GL45.glTextureStorage2D(names[3], 1, GL_RGB8, 4, 4);
            bytes.clear();
            for (int i = 0; i < 16; i++) {
                bytes.put(i * 3, (byte) 51);
                bytes.put(i * 3 + 1, (byte) 102);
                bytes.put(i * 3 + 2, (byte) 153);
            }
            GL45.glTextureSubImage2D(names[3], 0, 0, 0, 4, 4, GL_RGB, GL_UNSIGNED_BYTE, bytes);
            glBindTexture(GL_TEXTURE_2D, names[3]);
            sample(program, target, 0, 0, 4, 51, 102, 153);
            GL45.glTextureStorage2D(names[4], 1, GL_RGBA16F, 4, 4);
            for (int i = 0; i < 16; i++) {
                half.put(i * 4, Short.reverseBytes((short) 0x3266));
                half.put(i * 4 + 1, Short.reverseBytes((short) 0x3666));
                half.put(i * 4 + 2, Short.reverseBytes((short) 0x38cd));
                half.put(i * 4 + 3, Short.reverseBytes((short) 0x3c00));
            }
            glPixelStorei(GL_UNPACK_SWAP_BYTES, GL_TRUE);
            GL45.glTextureSubImage2D(names[4], 0, 0, 0, 4, 4, GL_RGBA, GL_HALF_FLOAT, half);
            glPixelStorei(GL_UNPACK_SWAP_BYTES, GL_FALSE);
            glBindTexture(GL_TEXTURE_2D, names[4]);
            sample(program, target, 0, 0, 4, 51, 102, 153);
            glUseProgram(0);
            glDeleteProgram(program);
            try {
                GL45.glTextureParameteri(names[0], GL_TEXTURE_WRAP_S, GL_CLAMP_TO_BORDER);
                throw new IllegalStateException("Unsupported border wrap silently accepted");
            } catch (UnsupportedOperationException expected) {
            }
            try {
                GL45.glTextureParameteri(names[0], GL_TEXTURE_SWIZZLE_R, GL_GREEN);
                throw new IllegalStateException("Unsupported named parameter accepted");
            } catch (UnsupportedOperationException expected) {
            }
            try {
                GL45.glTextureSubImage2D(names[0], 0, 0, 0, 4, 4, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
                throw new IllegalStateException("Unsupported integer upload conversion accepted");
            } catch (UnsupportedOperationException expected) {
            }
            try {
                bytes.limit(10);
                GL45.glTextureSubImage2D(names[2], 0, 0, 0, 3, 3, GL_RED, GL_UNSIGNED_BYTE, bytes);
                throw new IllegalStateException("Short upload buffer accepted");
            } catch (IllegalArgumentException expected) {
            }
        } finally {
            glPixelStorei(GL_UNPACK_SWAP_BYTES, GL_FALSE);
            glBindTexture(GL_TEXTURE_2D, 0);
            glDeleteTextures(names);
            MemoryUtil.memFree(words);
            MemoryUtil.memFree(half);
            MemoryUtil.memFree(bytes);
        }
    }

    private static void unchanged(int expected) {
        if (glGetInteger(GL_ACTIVE_TEXTURE) != GL_TEXTURE3 || glGetInteger(GL_TEXTURE_BINDING_2D) != expected)
            throw new IllegalStateException("Named operation changed texture unit bindings");
    }

    private static void resetUnpack() {
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
        glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, 0);
        glPixelStorei(GL_UNPACK_SKIP_IMAGES, 0);
        glPixelStorei(GL_UNPACK_SWAP_BYTES, GL_FALSE);
    }

    private static void color(java.nio.ByteBuffer data, int offset, int r, int g, int b) {
        data.put(offset, (byte) r)
                .put(offset + 1, (byte) g)
                .put(offset + 2, (byte) b)
                .put(offset + 3, (byte) 255);
    }

    private static int program(String sampler, String coordinate) {
        int vertex = glCreateShader(GL_VERTEX_SHADER),
                fragment = glCreateShader(GL_FRAGMENT_SHADER),
                program = glCreateProgram();
        glShaderSource(
                vertex,
                "#version 330 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0,1);}");
        glShaderSource(
                fragment,
                "#version 330 core\nuniform " + sampler
                        + " inputData;uniform int slice;uniform int mip;out vec4 color;void main(){color="
                        + (sampler.startsWith("u") ? "vec4(" : sampler.startsWith("i") ? "vec4(abs(" : "")
                        + "texelFetch(inputData,"
                        + coordinate + ",mip)"
                        + (sampler.startsWith("u") ? ")/255.0" : sampler.startsWith("i") ? "))/255.0" : "") + ";}");
        for (int shader : new int[] {vertex, fragment}) {
            glCompileShader(shader);
            if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
                throw new IllegalStateException(glGetShaderInfoLog(shader));
            glAttachShader(program, shader);
            glDeleteShader(shader);
        }
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
            throw new IllegalStateException(glGetProgramInfoLog(program));
        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program, "inputData"), 0);
        return program;
    }

    private static void sample(int program, int target, int mip, int slice, int size, int r, int g, int b) {
        glUniform1i(glGetUniformLocation(program, "mip"), mip);
        glUniform1i(glGetUniformLocation(program, "slice"), slice);
        glViewport(0, 0, size, size);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        verify(ResourceReadback.layer(target, 0), 8, size, size, r, g, b);
    }

    private static void verify(byte[] pixels, int stride, int width, int height, int r, int g, int b) {
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) pixel(pixels, (y * stride + x) * 4, r, g, b);
    }

    private static void pixel(byte[] pixels, int offset, int r, int g, int b) {
        int[] expected = {r, g, b, 255};
        for (int c = 0; c < 4; c++)
            if (Math.abs(Byte.toUnsignedInt(pixels[offset + c]) - expected[c]) > 1)
                throw new IllegalStateException("Named texture pixel " + offset / 4 + " channel " + c + " expected="
                        + expected[c] + " actual=" + Byte.toUnsignedInt(pixels[offset + c]));
        verified++;
    }
}
