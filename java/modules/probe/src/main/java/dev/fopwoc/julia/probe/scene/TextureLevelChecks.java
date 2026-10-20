package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.*;
import static org.lwjgl.opengl.GL43.glTextureView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.opengl.ARBDirectStateAccess;
import org.lwjgl.opengl.GL45;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryStack;

/** Named level queries read the object's own storage, including view-relative mips, without binding it. */
final class TextureLevelChecks {
    private TextureLevelChecks() {}

    static void run(Path output) throws IOException {
        int[] textures = {
            GL45.glCreateTextures(GL_TEXTURE_2D),
            GL45.glCreateTextures(GL_TEXTURE_3D),
            GL45.glCreateTextures(GL_TEXTURE_2D_ARRAY),
            glGenTextures(),
            glGenTextures(),
            glGenTextures()
        };
        int buffer = glGenBuffers();
        try (var stack = MemoryStack.stackPush()) {
            glActiveTexture(GL_TEXTURE0 + 110);
            glBindTexture(GL_TEXTURE_2D, textures[5]);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 7, 3, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            check(textures[0], 0, 0, 0, 0);
            GL45.glTextureStorage2D(textures[0], 3, GL_RGBA8, 16, 8);
            GL45.glTextureStorage3D(textures[1], 4, GL_RGBA8, 8, 4, 8);
            GL45.glTextureStorage3D(textures[2], 3, GL_RGBA8, 16, 8, 5);
            check(textures[0], 0, 16, 8, 1);
            check(textures[0], 2, 4, 2, 1);
            check(textures[0], 3, 0, 0, 0);
            check(textures[1], 2, 2, 1, 2);
            check(textures[2], 2, 4, 2, 5);
            glTextureView(textures[3], GL_TEXTURE_2D_ARRAY, textures[2], GL_RGBA8, 1, 2, 1, 3);
            check(textures[3], 0, 8, 4, 3);
            check(textures[3], 1, 4, 2, 3);
            check(textures[5], 0, 7, 3, 1);
            if (GL45.glGetTextureLevelParameteri(textures[0], 1, GL_TEXTURE_INTERNAL_FORMAT) != GL_RGBA8)
                throw new IllegalStateException("Named format query lost the GL internal format");
            var integers = stack.ints(-7, -7, -7).position(1).limit(2);
            GL45C.glGetTextureLevelParameteriv(textures[0], 1, GL_TEXTURE_WIDTH, integers);
            if (integers.position() != 1
                    || integers.limit() != 2
                    || integers.get(1) != 8
                    || integers.get(0) != -7
                    || integers.duplicate().clear().get(2) != -7)
                throw new IllegalStateException("Named integer query changed destination state or canaries");
            var floats = stack.floats(-7, -7, -7).position(1).limit(2);
            ARBDirectStateAccess.glGetTextureLevelParameterfv(textures[1], 1, GL_TEXTURE_DEPTH, floats);
            if (floats.position() != 1
                    || floats.get(1) != 4
                    || floats.get(0) != -7
                    || floats.duplicate().clear().get(2) != -7)
                throw new IllegalStateException("Named float query changed destination state or canaries");
            int[] ints = {-9, -9};
            float[] values = {-9, -9};
            GL45.glGetTextureLevelParameteriv(textures[2], 2, GL_TEXTURE_DEPTH, ints);
            GL45C.glGetTextureLevelParameterfv(textures[0], 2, GL_TEXTURE_HEIGHT, values);
            if (ints[0] != 5 || ints[1] != -9 || values[0] != 2 || values[1] != -9)
                throw new IllegalStateException("Named array query lost data or overwrote its tail");
            glBindBuffer(GL_TEXTURE_BUFFER, buffer);
            glBufferData(GL_TEXTURE_BUFFER, stack.ints(1, 2, 3, 4), GL_STATIC_DRAW);
            glBindTexture(GL_TEXTURE_BUFFER, textures[4]);
            glTexBuffer(GL_TEXTURE_BUFFER, GL_R32UI, buffer);
            if (GL45.glGetTextureLevelParameteri(textures[4], 0, GL_TEXTURE_WIDTH) != 4)
                throw new IllegalStateException("Named buffer-texture query lost texel count");
            try {
                GL45.glGetTextureLevelParameteri(textures[0], -1, GL_TEXTURE_WIDTH);
                throw new AssertionError("Negative mip accepted");
            } catch (IllegalArgumentException expected) {
            }
            try {
                GL45.glGetTextureLevelParameteri(textures[0], 0, GL_TEXTURE_RED_SIZE);
                throw new AssertionError("Unimplemented query silently accepted");
            } catch (UnsupportedOperationException expected) {
            }
            glDeleteTextures(textures[3]);
            try {
                GL45.glGetTextureLevelParameteri(textures[3], 0, GL_TEXTURE_WIDTH);
                throw new AssertionError("Deleted named texture accepted");
            } catch (IllegalArgumentException expected) {
            }
            textures[3] = 0;
            if (glGetInteger(GL_ACTIVE_TEXTURE) != GL_TEXTURE0 + 110
                    || glGetInteger(GL_TEXTURE_BINDING_2D) != textures[5]
                    || glGetInteger(GL_TEXTURE_BINDING_BUFFER) != textures[4])
                throw new IllegalStateException("Named query changed texture bindings");
            Files.writeString(
                    output.resolve("texture-levels.txt"),
                    "PASS\nchecks=2D/3D/array dimensions,mip bounds,view-relative mips/layers,mutable storage,buffer texels,GL format,scalar/array/buffer overloads,canaries,binding independence,invalid/deleted names\n");
        } finally {
            for (int texture : textures) if (texture != 0) glDeleteTextures(texture);
            glDeleteBuffers(buffer);
            glActiveTexture(GL_TEXTURE0);
        }
    }

    private static void check(int name, int level, int width, int height, int depth) {
        if (GL45.glGetTextureLevelParameteri(name, level, GL_TEXTURE_WIDTH) != width
                || GL45C.glGetTextureLevelParameterf(name, level, GL_TEXTURE_HEIGHT) != height
                || ARBDirectStateAccess.glGetTextureLevelParameteri(name, level, GL_TEXTURE_DEPTH) != depth)
            throw new IllegalStateException("Named texture mip dimensions differ: " + name + ":" + level);
    }
}
