package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;

import dev.fopwoc.julia.backend.BackendInternals;
import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import org.lwjgl.sdl.SDLGPU;
import org.lwjgl.system.MemoryUtil;

/** Video-style CPU pointer uploads, including padded RGB rows and nondefault unpack layout. */
final class ClientPointerChecks {
    private ClientPointerChecks() {}

    static void run() {
        int texture = glGenTextures();
        try {
            glBindTexture(GL_TEXTURE_2D, texture);
            for (int test = 0; test < 3; test++) {
                int channels = test == 1 ? 3 : 4;
                int stride = test == 2 ? 20 : 12;
                int start = test == 2 ? 24 : 0;
                int span = start + stride + 3 * channels;
                int red = 51 + test * 20, green = 102, blue = 153;
                var source = MemoryUtil.memCalloc(span + 1);
                try {
                    for (int y = 0; y < 2; y++)
                        for (int x = 0; x < 3; x++) {
                            int offset = 1 + start + y * stride + x * channels;
                            source.put(offset, (byte) red)
                                    .put(offset + 1, (byte) green)
                                    .put(offset + 2, (byte) blue);
                            if (channels == 4) source.put(offset + 3, (byte) 255);
                        }
                    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
                    glPixelStorei(GL_UNPACK_ROW_LENGTH, test == 2 ? 5 : 0);
                    glPixelStorei(GL_UNPACK_SKIP_ROWS, test == 2 ? 1 : 0);
                    glPixelStorei(GL_UNPACK_SKIP_PIXELS, test == 2 ? 1 : 0);
                    long pointer = MemoryUtil.memAddress(source) + 1;
                    glTexImage2D(
                            GL_TEXTURE_2D,
                            0,
                            GL_RGBA,
                            3,
                            2,
                            0,
                            channels == 3 ? GL_RGB : GL_RGBA,
                            GL_UNSIGNED_BYTE,
                            pointer);
                    // GL owns a copy when the upload returns; the video decoder can reuse its buffer.
                    MemoryUtil.memSet(pointer, 0, span);
                    var pixels = ResourceReadback.layer(texture, 0);
                    boolean bgra =
                            BackendInternals.resources().getTextureMeta(texture).sdlFormat()
                                    == SDLGPU.SDL_GPU_TEXTUREFORMAT_B8G8R8A8_UNORM;
                    int[] expected = bgra ? new int[] {blue, green, red, 255} : new int[] {red, green, blue, 255};
                    for (int i = 0; i < 6; i++)
                        for (int c = 0; c < 4; c++)
                            if ((pixels[i * 4 + c] & 255) != expected[c])
                                throw new IllegalStateException("Client pointer upload " + test + " pixel " + i
                                        + " channel " + c + " got " + (pixels[i * 4 + c] & 255));
                } finally {
                    MemoryUtil.memFree(source);
                }
            }
        } finally {
            glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
            glBindTexture(GL_TEXTURE_2D, 0);
            glDeleteTextures(texture);
        }
    }
}
