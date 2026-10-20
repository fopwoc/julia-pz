package dev.fopwoc.julia.backend.bridge;

import static org.lwjgl.opengl.GL31.GL_COPY_READ_BUFFER;
import static org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import dev.fopwoc.julia.backend.BackendInternals;
import java.nio.ByteBuffer;
import java.util.Objects;

/** Named buffer transfers preserve bindings and use the real upstream GPU copy and readback paths. */
public final class BufferTransferBridge {
    private BufferTransferBridge() {}

    public static void glCopyNamedBufferSubData(
            int source, int destination, long sourceOffset, long destinationOffset, long size) {
        range(source, sourceOffset, size);
        range(destination, destinationOffset, size);
        if (source == destination
                && size > 0
                && sourceOffset < destinationOffset + size
                && destinationOffset < sourceOffset + size)
            throw new IllegalArgumentException("Overlapping same-buffer copy");
        if (size == 0) return;
        if (BackendInternals.frames().getCommandBuffer() == 0)
            throw new IllegalStateException("Named GPU copy requires an active frame");
        var state = BackendInternals.context();
        int read = state.boundCopyReadBuffer, write = state.boundCopyWriteBuffer;
        try {
            GLStateManager.glBindBuffer(GL_COPY_READ_BUFFER, source);
            GLStateManager.glBindBuffer(GL_COPY_WRITE_BUFFER, destination);
            GLStateManager.glCopyBufferSubData(
                    GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, sourceOffset, destinationOffset, size);
        } finally {
            GLStateManager.glBindBuffer(GL_COPY_WRITE_BUFFER, write);
            GLStateManager.glBindBuffer(GL_COPY_READ_BUFFER, read);
        }
    }

    public static void glGetNamedBufferSubData(int buffer, long offset, ByteBuffer result) {
        Objects.requireNonNull(result);
        if (!result.isDirect() || result.isReadOnly())
            throw new IllegalArgumentException("Named buffer readback requires writable direct storage");
        range(buffer, offset, result.remaining());
        if (!result.hasRemaining()) return;
        var state = BackendInternals.context();
        int read = state.boundCopyReadBuffer;
        try {
            GLStateManager.glBindBuffer(GL_COPY_READ_BUFFER, buffer);
            GLStateManager.glGetBufferSubData(GL_COPY_READ_BUFFER, offset, result.duplicate());
        } finally {
            GLStateManager.glBindBuffer(GL_COPY_READ_BUFFER, read);
        }
    }

    private static void range(int buffer, long offset, long length) {
        if (offset < 0 || length < 0) throw new IllegalArgumentException("Negative buffer transfer range");
        if (!GLStateManager.glIsBuffer(buffer))
            throw new IllegalArgumentException("Named buffer does not exist: " + buffer);
        long size = BackendInternals.resources().getBufferSize(buffer);
        if (offset > size || length > size - offset)
            throw new IllegalArgumentException("Buffer transfer exceeds storage");
    }
}
