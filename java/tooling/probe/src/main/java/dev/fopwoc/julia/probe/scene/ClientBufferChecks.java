package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL21.*;
import static org.lwjgl.opengl.GL30.*;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.system.MemoryUtil;

/** Native pixel allocation bounds are independent of narrowed NIO view limits. */
final class ClientBufferChecks {
    private static final int[] BASE = {17, 34, 51, 255};
    private static final int[] FIRST = {99, 100, 101, 255};
    private static final int[] SECOND = {22, 23, 24, 128};

    private ClientBufferChecks() {}

    static void run(Path output) throws IOException {
        int texture = glGenTextures(), alignment = glGetInteger(GL_UNPACK_ALIGNMENT);
        var data = MemoryUtil.memAlloc(512 * 512 * 4);
        var sub = MemoryUtil.memAlloc(12);
        try {
            for (int i = 0; i < data.capacity(); i += 4)
                data.put(i, (byte) 17)
                        .put(i + 1, (byte) 34)
                        .put(i + 2, (byte) 51)
                        .put(i + 3, (byte) 255);
            data.limit(0);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 512, 512, 0, GL_RGBA, GL_UNSIGNED_BYTE, data);
            require(data.position() == 0 && data.limit() == 0, "image upload changed source bounds");
            check(ResourceReadback.layer(texture, 0), false);
            sub.put(4, (byte) 99).put(5, (byte) 100).put(6, (byte) 101).put(7, (byte) 255);
            sub.put(8, (byte) 22).put(9, (byte) 23).put(10, (byte) 24).put(11, (byte) 128);
            sub.position(4).limit(4);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 5, 7, 2, 1, GL_RGBA, GL_UNSIGNED_BYTE, sub);
            require(sub.position() == 4 && sub.limit() == 4, "subimage upload changed source bounds");
            check(ResourceReadback.layer(texture, 0), true);
            require(glGetInteger(GL_UNPACK_ALIGNMENT) == 1, "upload changed unpack alignment");
            pixelBufferRows();
            Files.writeString(
                    output.resolve("client-buffers.txt"),
                    "PASS\nverified_pixels=524300\nchecks=512x512 RGBA native allocation with zero NIO limit,offset zero-limit subimage,source bounds and unpack state preserved,padded pixel-buffer image and subimage\n");
        } finally {
            glPixelStorei(GL_UNPACK_ALIGNMENT, alignment);
            glDeleteTextures(texture);
            MemoryUtil.memFree(data);
            MemoryUtil.memFree(sub);
        }
    }

    private static void check(byte[] pixels, boolean modified) {
        int first = (7 * 512 + 5) * 4;
        for (int at = 0; at < pixels.length; at += 4) {
            int[] expected = modified && at == first ? FIRST : modified && at == first + 4 ? SECOND : BASE;
            for (int c = 0; c < 4; c++)
                if ((pixels[at + c] & 255) != expected[c])
                    throw new IllegalStateException("Client pixel buffer: pixel=" + (at / 4) + ", channel=" + c
                            + ", got=" + (pixels[at + c] & 255));
        }
    }

    private static void pixelBufferRows() {
        int texture = glGenTextures(), buffer = glGenBuffers();
        int previous = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING), rowLength = glGetInteger(GL_UNPACK_ROW_LENGTH);
        var data = MemoryUtil.memCalloc(30);
        try {
            for (int y = 0; y < 2; y++)
                for (int x = 0; x < 3; x++)
                    for (int c = 0; c < 4; c++) data.put(2 + y * 16 + x * 4 + c, (byte) BASE[c]);
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, buffer);
            glBufferData(GL_PIXEL_UNPACK_BUFFER, (long) data.remaining(), GL_STATIC_DRAW);
            var mapped = glMapBufferRange(GL_PIXEL_UNPACK_BUFFER, 0, data.remaining(), GL_MAP_WRITE_BIT);
            require(mapped != null, "cannot map pixel-buffer upload");
            mapped.put(data);
            require(glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER), "cannot unmap pixel-buffer upload");
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 4);
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 3, 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, 2L);
            var pixels = ResourceReadback.layer(texture, 0);
            for (int i = 0; i < 24; i++)
                require((pixels[i] & 255) == BASE[i % 4], "padded PBO image at byte " + i + ": " + (pixels[i] & 255));
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 3, 2, GL_RGBA, GL_UNSIGNED_BYTE, 2L);
            pixels = ResourceReadback.layer(texture, 0);
            for (int i = 0; i < 24; i++)
                require(
                        (pixels[i] & 255) == BASE[i % 4],
                        "padded PBO subimage at byte " + i + ": " + (pixels[i] & 255));
        } finally {
            glPixelStorei(GL_UNPACK_ROW_LENGTH, rowLength);
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, previous);
            glDeleteBuffers(buffer);
            glDeleteTextures(texture);
            MemoryUtil.memFree(data);
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException("Client pixel buffer: " + message);
    }
}
