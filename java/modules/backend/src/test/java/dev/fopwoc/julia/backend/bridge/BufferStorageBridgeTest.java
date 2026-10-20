package dev.fopwoc.julia.backend.bridge;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class BufferStorageBridgeTest {
    @Test
    void rejectsUnsafeOrEmptyInputBeforeBackendAccess() {
        assertThrows(
                IllegalArgumentException.class,
                () -> BufferStorageBridge.glNamedBufferStorage(1, ByteBuffer.allocate(8), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> BufferStorageBridge.glNamedBufferStorage(1, ByteBuffer.allocateDirect(0), 0));
    }

    @Test
    void validatesSizeOnlyStorageBeforeBackendAccess() {
        for (long size : new long[] {0, -1, Long.MIN_VALUE})
            assertThrows(IllegalArgumentException.class, () -> BufferStorageBridge.glNamedBufferStorage(1, size, 0));
        assertThrows(IllegalArgumentException.class, () -> BufferStorageBridge.glNamedBufferStorage(0, 8L, 0));
        assertThrows(
                UnsupportedOperationException.class,
                () -> BufferStorageBridge.glNamedBufferStorage(1, (long) Integer.MAX_VALUE + 1, 0));
        assertThrows(IllegalArgumentException.class, () -> BufferStorageBridge.glNamedBufferStorage(1, 8L, 4));
    }

    @Test
    void enforcesPersistentAndCoherentMappingFlagDependencies() {
        for (int flags : new int[] {0, 0x100, 1, 2, 0x41, 0x42, 0xC2, 0x3C3}) {
            assertDoesNotThrow(() -> BufferStorageBridge.validateFlags(flags));
        }
        for (int flags : new int[] {4, -1, 0x40, 0x80, 0x82, 0x400}) {
            assertThrows(IllegalArgumentException.class, () -> BufferStorageBridge.validateFlags(flags));
        }
    }
}
