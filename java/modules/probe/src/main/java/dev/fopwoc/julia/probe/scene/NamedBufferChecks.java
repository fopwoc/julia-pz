package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL30.GL_MAP_READ_BIT;
import static org.lwjgl.opengl.GL31.*;
import static org.lwjgl.opengl.GL44.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.opengl.ARBDirectStateAccess;
import org.lwjgl.opengl.GL45;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryStack;

/** Empty named storage receives GPU copies without client-write permission, then returns exact bytes. */
final class NamedBufferChecks {
    private NamedBufferChecks() {}

    static void run(Path output) throws IOException {
        int source = GL45.glCreateBuffers(), destination = GL45.glCreateBuffers(), mapped = GL45.glCreateBuffers();
        int sentinel = GL45.glCreateBuffers(), legacy = glGenBuffers();
        try (var stack = MemoryStack.stackPush()) {
            if (!glIsBuffer(source) || !glIsBuffer(destination))
                throw new IllegalStateException("Empty created buffer lost object identity");
            if (glIsBuffer(legacy))
                throw new IllegalStateException("Generated name became an object before first binding");
            glBindBuffer(GL_COPY_READ_BUFFER, legacy);
            glBindBuffer(GL_COPY_READ_BUFFER, sentinel);
            if (!glIsBuffer(legacy)) throw new IllegalStateException("Unbinding empty storage lost buffer identity");
            GL45.glNamedBufferStorage(legacy, 8L, 0);
            glBindBuffer(GL_COPY_WRITE_BUFFER, sentinel);
            GL45.glNamedBufferStorage(sentinel, 4L, 0);
            var data = stack.malloc(64);
            for (int i = 0; i < 64; i++) data.put(i, (byte) (i * 3 + 7));
            GL45C.glNamedBufferStorage(source, data, 0);
            GL45.glNamedBufferStorage(destination, 80L, GL_CLIENT_STORAGE_BIT);
            ARBDirectStateAccess.glNamedBufferStorage(mapped, 8L, GL_MAP_READ_BIT);
            GL45.glCopyNamedBufferSubData(source, destination, 0, 0, 64);
            GL45C.glCopyNamedBufferSubData(source, destination, 0, 64, 16);
            ARBDirectStateAccess.glCopyNamedBufferSubData(destination, destination, 0, 32, 8);
            var bytes = stack.malloc(84);
            for (int i = 0; i < bytes.capacity(); i++) bytes.put(i, (byte) -1);
            bytes.position(2).limit(82);
            GL45.glGetNamedBufferSubData(destination, 0, bytes);
            if (bytes.position() != 2
                    || bytes.limit() != 82
                    || bytes.get(0) != -1
                    || bytes.get(1) != -1
                    || bytes.duplicate().clear().get(82) != -1)
                throw new IllegalStateException("Named readback changed caller state or canaries");
            for (int i = 0; i < 80; i++) {
                int at = i >= 64 ? i - 64 : i >= 32 && i < 40 ? i - 32 : i;
                if (bytes.get(2 + i) != data.get(at))
                    throw new IllegalStateException("GPU buffer copy mismatch at " + i);
            }
            GL45C.glGetNamedBufferSubData(source, 0, bytes.duplicate().clear().limit(4));
            ARBDirectStateAccess.glGetNamedBufferSubData(
                    source, 0, bytes.duplicate().clear().limit(4));
            reject(() -> GL45.glCopyNamedBufferSubData(destination, destination, 0, 1, 8));
            reject(() -> GL45.glCopyNamedBufferSubData(source, destination, 63, 0, 2));
            reject(() -> GL45.glCopyNamedBufferSubData(source, destination, 0, Long.MAX_VALUE, 2));
            reject(() -> GL45.glGetNamedBufferSubData(destination, 79, bytes));
            reject(() -> GL45.glGetNamedBufferSubData(destination, 0, ByteBuffer.allocate(4)));
            reject(() -> GL45.glGetNamedBufferSubData(destination, 0, bytes.asReadOnlyBuffer()));
            try {
                GL45.glNamedBufferStorage(destination, 16L, 0);
                throw new AssertionError("Immutable storage redefined");
            } catch (IllegalStateException expected) {
            }
            if (glGetInteger(GL_COPY_READ_BUFFER_BINDING) != sentinel
                    || glGetInteger(GL_COPY_WRITE_BUFFER_BINDING) != sentinel)
                throw new IllegalStateException("Named transfer changed copy bindings");
            glBindBuffer(GL_COPY_READ_BUFFER, destination);
            if (glGetBufferParameteri(GL_COPY_READ_BUFFER, GL_BUFFER_SIZE) != 80
                    || glGetBufferParameteri(GL_COPY_READ_BUFFER, GL_BUFFER_STORAGE_FLAGS) != GL_CLIENT_STORAGE_BIT
                    || glGetBufferParameteri(GL_COPY_READ_BUFFER, GL_BUFFER_IMMUTABLE_STORAGE) != GL_TRUE)
                throw new IllegalStateException("Size-only immutable metadata changed");
            glDeleteBuffers(mapped);
            if (glIsBuffer(mapped)) throw new IllegalStateException("Deleted empty-buffer identity survived");
            reject(() -> GL45.glNamedBufferStorage(mapped, 8L, 0));
            Files.writeString(
                    output.resolve("named-buffers.txt"),
                    "PASS\nverified_bytes=80\nchecks=empty created identity,size-only immutable storage,client-storage/read flags,GPU copies without dynamic-storage permission,nonoverlapping same-buffer copy,exact readback,caller state,range/overlap rejection,deletion\n");
        } finally {
            glBindBuffer(GL_COPY_READ_BUFFER, 0);
            glBindBuffer(GL_COPY_WRITE_BUFFER, 0);
            for (int name : new int[] {source, destination, mapped, sentinel, legacy}) glDeleteBuffers(name);
        }
    }

    private static void reject(Runnable operation) {
        try {
            operation.run();
        } catch (IllegalArgumentException | UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("Invalid named buffer operation accepted");
    }
}
