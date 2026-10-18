package dev.fopwoc.julia.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class CapabilitySnapshotTest {
    @Test
    void startsLazilyThenReadsFromWorkersWithoutEnteringStartupAgain() throws Exception {
        StartupHooks.install(() -> CapabilitySnapshot.publish(Set.of("OpenGL33")));
        try {
            assertEquals(Set.of("OpenGL33"), CapabilitySnapshot.current());
            assertEquals(
                    Set.of("OpenGL33"),
                    CompletableFuture.supplyAsync(CapabilitySnapshot::current).get());
        } finally {
            StartupHooks.close();
        }
        assertThrows(IllegalStateException.class, CapabilitySnapshot::current);
    }

    @Test
    void publishesImmutableCopyAcrossThreadsAndRevokesOnClose() throws Exception {
        var flags = new HashSet<>(Set.of("OpenGL33"));
        var snapshot = CapabilitySnapshot.publish(flags);
        try {
            flags.clear();
            assertEquals(
                    Set.of("OpenGL33"),
                    CompletableFuture.supplyAsync(CapabilitySnapshot::current).get());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> CapabilitySnapshot.current().clear());
            assertThrows(IllegalStateException.class, () -> CapabilitySnapshot.publish(Set.of()));
            var closeFailure =
                    CompletableFuture.supplyAsync(() -> assertThrows(IllegalStateException.class, snapshot::close));
            assertNotNull(closeFailure.get());
            assertEquals(Set.of("OpenGL33"), CapabilitySnapshot.current());
            // An immutable snapshot must not relax dispatch ownership for actual GPU calls.
            var draw = RenderDispatch.bootstrap(
                            MethodHandles.lookup(), "draw", MethodType.methodType(void.class), "test/Gpu")
                    .dynamicInvoker();
            var drawFailure = CompletableFuture.supplyAsync(() -> {
                try {
                    draw.invokeExact();
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });
            assertInstanceOf(IllegalStateException.class, drawFailure.get());
        } finally {
            snapshot.close();
        }
        assertThrows(IllegalStateException.class, CapabilitySnapshot::current);
        var next = CapabilitySnapshot.publish(Set.of("OpenGL45"));
        try (next) {
            snapshot.close();
            assertEquals(Set.of("OpenGL45"), CapabilitySnapshot.current());
        }
    }
}
