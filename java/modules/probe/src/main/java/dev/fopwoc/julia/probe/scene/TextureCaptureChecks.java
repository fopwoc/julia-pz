package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL21.*;
import static org.lwjgl.opengl.GL30.glGenerateMipmap;
import static org.lwjgl.opengl.GL42.glTexStorage2D;

import dev.fopwoc.julia.backend.diagnostic.TextureCapture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.system.MemoryStack;

/** Explicit captures preserve caller bindings and packing while downloading exact alpha and mip data. */
final class TextureCaptureChecks {
    private TextureCaptureChecks() {}

    static void run(Path output) throws IOException {
        int texture = glGenTextures(), sentinel = glGenTextures(), pack = glGenBuffers();
        Path capture = output.resolve("capture-check");
        Files.createDirectories(capture);
        try (var stack = MemoryStack.stackPush()) {
            glActiveTexture(GL_TEXTURE0 + 37);
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexStorage2D(GL_TEXTURE_2D, 2, GL_RGBA8, 2, 2);
            var pixels = stack.malloc(16);
            for (int i = 0; i < 16; i++) pixels.put(i, (byte) (i % 4 == 3 ? (i < 8 ? 0 : 255) : 128));
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 2, 2, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            glGenerateMipmap(GL_TEXTURE_2D);
            glBindTexture(GL_TEXTURE_2D, sentinel);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, pack);
            glBufferData(GL_PIXEL_PACK_BUFFER, 128, GL_STREAM_READ);
            glPixelStorei(GL_PACK_ALIGNMENT, 8);
            glPixelStorei(GL_PACK_ROW_LENGTH, 7);
            glPixelStorei(GL_PACK_SKIP_PIXELS, 2);
            glPixelStorei(GL_PACK_SKIP_ROWS, 1);
            Files.writeString(capture.resolve("texture-capture.request"), texture + " 0\n" + texture + " 1\n");
            TextureCapture.poll(capture, 1);
            byte[] base = Files.readAllBytes(capture.resolve("textures-1/" + texture + "-mip0-2x2.rgba"));
            if (base.length != 16) throw new IllegalStateException("Capture base image size differs");
            for (int i = 0; i < 16; i++)
                if (base[i] != pixels.get(i)) throw new IllegalStateException("Capture lost image channel " + i);
            byte[] mip = Files.readAllBytes(capture.resolve("textures-1/" + texture + "-mip1-1x1.rgba"));
            if (mip.length != 4) throw new IllegalStateException("Capture mip size differs");
            for (byte value : mip)
                if (Math.abs((value & 255) - 128) > 1) throw new IllegalStateException("Capture lost mip average");
            if (glGetInteger(GL_ACTIVE_TEXTURE) != GL_TEXTURE0 + 37
                    || glGetInteger(GL_TEXTURE_BINDING_2D) != sentinel
                    || glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING) != pack
                    || glGetInteger(GL_PACK_ALIGNMENT) != 8
                    || glGetInteger(GL_PACK_ROW_LENGTH) != 7
                    || glGetInteger(GL_PACK_SKIP_PIXELS) != 2
                    || glGetInteger(GL_PACK_SKIP_ROWS) != 1)
                throw new IllegalStateException("Texture capture changed caller state");
            if (Files.exists(capture.resolve("texture-capture.request")))
                throw new IllegalStateException("Texture capture request was not consumed");
            Files.writeString(
                    output.resolve("texture-capture.txt"),
                    "PASS\nchecks=exact RGBA alpha,mip averages,nondefault pack state,bindings,request consumption\n");
        } finally {
            glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
            glPixelStorei(GL_PACK_ALIGNMENT, 4);
            glPixelStorei(GL_PACK_ROW_LENGTH, 0);
            glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
            glPixelStorei(GL_PACK_SKIP_ROWS, 0);
            glDeleteBuffers(pack);
            glDeleteTextures(texture);
            glDeleteTextures(sentinel);
            glActiveTexture(GL_TEXTURE0);
        }
    }
}
