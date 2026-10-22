package dev.fopwoc.julia.backend.diagnostic;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.sdl.SDLGPU.SDL_GPU_TEXTUREFORMAT_R8G8B8A8_UNORM;

import dev.fopwoc.julia.backend.Upstream;
import dev.fopwoc.julia.backend.bridge.TextureReadbackBridge;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.lwjgl.system.MemoryUtil;

/** Explicit, bounded RGBA8 texture downloads for diagnosing image data independently of shader execution. */
public final class TextureCapture {
    private static final long LIMIT = 64L * 1024 * 1024;

    private TextureCapture() {}

    /** Each request line names a GL texture and mip level, separated by whitespace. */
    public static void poll(Path output, long frame) throws IOException {
        Path request = output.resolve("texture-capture.request");
        if (!Files.isRegularFile(request)) return;
        if (Files.size(request) > 4096) throw new IllegalArgumentException("Texture capture request exceeds 4 KiB");
        var lines = Files.readAllLines(request);
        if (lines.size() > 32) throw new IllegalArgumentException("Texture capture exceeds 32 images");
        Files.delete(request);
        Path destination = output.resolve("textures-" + frame);
        Files.createDirectories(destination);
        long remaining = LIMIT;
        for (String line : lines) {
            if (line.isBlank()) continue;
            var fields = line.trim().split("\\s+");
            if (fields.length != 2) throw new IllegalArgumentException("Expected texture name and mip level");
            int name = Integer.parseInt(fields[0]), level = Integer.parseInt(fields[1]);
            var meta = Upstream.resources().getTextureMeta(name);
            if (meta == null
                    || meta.glTarget() != GL_TEXTURE_2D
                    || meta.sdlFormat() != SDL_GPU_TEXTUREFORMAT_R8G8B8A8_UNORM
                    || level < 0
                    || level >= meta.levels())
                throw new IllegalArgumentException("Capture requires an allocated 2D RGBA8 mip");
            int width = Math.max(1, meta.width() >> level), height = Math.max(1, meta.height() >> level);
            long size = Math.multiplyExact(Math.multiplyExact((long) width, height), 4);
            if (size > remaining) throw new IllegalArgumentException("Texture capture exceeds 64 MiB");
            remaining -= size;
            var state = Upstream.context();
            var store = state.pixelStore;
            int buffer = state.boundPixelPackBuffer, alignment = store.packAlignment;
            int row = store.packRowLength, x = store.packSkipPixels, y = store.packSkipRows;
            var pixels = MemoryUtil.memAlloc(Math.toIntExact(size));
            try {
                state.boundPixelPackBuffer = 0;
                store.packAlignment = 1;
                store.packRowLength = store.packSkipPixels = store.packSkipRows = 0;
                TextureReadbackBridge.glGetTextureImage(name, level, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                String stem = name + "-mip" + level + "-" + width + "x" + height;
                try (var channel = Files.newByteChannel(
                        destination.resolve(stem + ".rgba"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    while (pixels.hasRemaining()) channel.write(pixels);
                }
            } finally {
                state.boundPixelPackBuffer = buffer;
                store.packAlignment = alignment;
                store.packRowLength = row;
                store.packSkipPixels = x;
                store.packSkipRows = y;
                MemoryUtil.memFree(pixels);
            }
        }
    }
}
