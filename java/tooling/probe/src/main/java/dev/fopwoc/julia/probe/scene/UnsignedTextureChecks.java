package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.system.MemoryStack;

/** Java negative integers retain all 32 unsigned GL object-name bits. */
final class UnsignedTextureChecks {
    private UnsignedTextureChecks() {}

    static void run(Path output) throws IOException {
        int[] names = {-1, -2, -3, -4, -16, -35, Integer.MIN_VALUE};
        try (var stack = MemoryStack.stackPush()) {
            var pixel = stack.bytes((byte) 71, (byte) 83, (byte) 149, (byte) 255);
            for (int name : names) {
                glBindTexture(GL_TEXTURE_2D, name);
                if (!glIsTexture(name) || glGetInteger(GL_TEXTURE_BINDING_2D) != name)
                    throw new IllegalStateException(
                            "Unsigned texture binding failed: " + Integer.toUnsignedString(name));
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
                var bytes = ResourceReadback.layer(name, 0);
                for (int c = 0; c < 4; c++)
                    if (bytes[c] != pixel.get(c)) throw new IllegalStateException("Unsigned texture upload failed");
                glDeleteTextures(name);
                if (glIsTexture(name)) throw new IllegalStateException("Unsigned texture deletion failed");
                glBindTexture(GL_TEXTURE_2D, name);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
                bytes = ResourceReadback.layer(name, 0);
                for (int c = 0; c < 4; c++)
                    if (bytes[c] != pixel.get(c)) throw new IllegalStateException("Unsigned texture reuse failed");
                glDeleteTextures(name);
            }
            Files.writeString(
                    output.resolve("unsigned-textures.txt"),
                    "PASS\nverified_pixels=14\nchecks=high-bit texture binding,upload,query,deletion,reuse\n");
        } finally {
            glBindTexture(GL_TEXTURE_2D, 0);
            for (int name : names) glDeleteTextures(name);
        }
    }
}
