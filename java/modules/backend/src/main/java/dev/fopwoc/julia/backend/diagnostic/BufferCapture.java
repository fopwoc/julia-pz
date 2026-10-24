package dev.fopwoc.julia.backend.diagnostic;

import dev.fopwoc.julia.backend.Upstream;
import dev.fopwoc.julia.backend.bridge.BufferTransferBridge;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.lwjgl.system.MemoryUtil;

/** Explicit, bounded GPU buffer downloads; each request line contains a GL buffer name, byte offset and byte count. */
public final class BufferCapture {
    private static final long LIMIT = 64L * 1024 * 1024;

    private BufferCapture() {}

    public static void poll(Path output, long frame) throws IOException {
        Path request = output.resolve("buffer-capture.request");
        if (!Files.isRegularFile(request)) return;
        if (Files.size(request) > 4096) throw new IllegalArgumentException("Buffer capture request exceeds 4 KiB");
        var lines = Files.readAllLines(request);
        if (lines.size() > 32) throw new IllegalArgumentException("Buffer capture exceeds 32 ranges");
        Files.delete(request);
        Path destination = output.resolve("buffers-" + frame);
        Files.createDirectories(destination);
        long remaining = LIMIT;
        for (String line : lines) {
            if (line.isBlank()) continue;
            var fields = line.trim().split("\\s+");
            if (fields.length != 3) throw new IllegalArgumentException("Expected buffer name, offset and byte count");
            int name = Integer.parseInt(fields[0]);
            long offset = Long.parseLong(fields[1]), size = Long.parseLong(fields[2]);
            long available = Upstream.resources().getBufferSize(name);
            if (name <= 0 || offset < 0 || size <= 0 || offset > available || size > available - offset)
                throw new IllegalArgumentException("Buffer capture range exceeds allocated storage");
            if (size > remaining) throw new IllegalArgumentException("Buffer capture exceeds 64 MiB");
            remaining -= size;
            var data = MemoryUtil.memAlloc(Math.toIntExact(size));
            try {
                BufferTransferBridge.glGetNamedBufferSubData(name, offset, data);
                Path file = destination.resolve(name + "-offset" + offset + "-bytes" + size + ".bin");
                try (var channel =
                        Files.newByteChannel(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    while (data.hasRemaining()) channel.write(data);
                }
            } finally {
                MemoryUtil.memFree(data);
            }
        }
    }
}
