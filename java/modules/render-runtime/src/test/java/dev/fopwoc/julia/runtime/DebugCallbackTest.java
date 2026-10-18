package dev.fopwoc.julia.runtime;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class DebugCallbackTest {
    public interface Contract {
        void invoke(int source, int type, int id, int severity, int length, long message, long userParam);
    }

    @Test
    void bindsPrivateLambdaThroughPublicContractAndPreservesEveryArgument() throws Throwable {
        long[] received = new long[7];
        Contract callback = (s, t, i, v, l, m, p) -> {
            long[] values = {s, t, i, v, l, m, p};
            System.arraycopy(values, 0, received, 0, values.length);
        };
        var handle = DebugCallback.bind(callback, Contract.class);
        assertEquals(DebugCallback.TYPE, handle.type());
        handle.invokeExact(1, 2, 3, 4, 5, 0x123456789L, 0x987654321L);
        assertArrayEquals(new long[] {1, 2, 3, 4, 5, 0x123456789L, 0x987654321L}, received);
        assertNull(DebugCallback.bind(null, Contract.class));
    }

    @Test
    void rejectsObjectsOutsideTheContractAndPropagatesCallbackFailures() {
        assertThrows(IllegalArgumentException.class, () -> DebugCallback.bind(new Object(), Contract.class));
        assertThrows(IllegalArgumentException.class, () -> DebugCallback.bind(new Object(), Object.class));
        var failure = new IllegalStateException("callback failure");
        Contract callback = (s, t, i, v, l, m, p) -> {
            throw failure;
        };
        var handle = DebugCallback.bind(callback, Contract.class);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> {
            handle.invokeExact(1, 2, 3, 4, 5, 6L, 7L);
        }));
    }
}
