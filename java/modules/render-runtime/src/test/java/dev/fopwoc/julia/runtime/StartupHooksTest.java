package dev.fopwoc.julia.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StartupHooksTest {
    @Test
    void initializesOnceOnCallerAndRejectsOtherThreadsThenClosesOnOwner() throws Exception {
        var starts = new AtomicInteger();
        var closes = new AtomicInteger();
        StartupHooks.install(() -> {
            starts.incrementAndGet();
            return closes::incrementAndGet;
        });
        try {
            StartupHooks.ensure();
            StartupHooks.ensure();
            assertEquals(1, starts.get());
            var foreign = new AtomicReference<Throwable>();
            var worker = new Thread(() -> {
                try {
                    StartupHooks.ensure();
                } catch (Throwable failure) {
                    foreign.set(failure);
                }
            });
            worker.start();
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertInstanceOf(IllegalStateException.class, foreign.get());
        } finally {
            StartupHooks.close();
        }
        assertEquals(1, closes.get());
        StartupHooks.close();
        assertEquals(1, closes.get());
    }

    @Test
    void failedStartupIsStickyAndDoesNotRetryNativeInitialization() {
        var starts = new AtomicInteger();
        StartupHooks.install(() -> {
            starts.incrementAndGet();
            throw new IllegalStateException("fixture failure");
        });
        try {
            assertThrows(IllegalStateException.class, StartupHooks::ensure);
            assertThrows(IllegalStateException.class, StartupHooks::ensure);
            assertEquals(1, starts.get());
        } finally {
            StartupHooks.close();
        }
    }
}
