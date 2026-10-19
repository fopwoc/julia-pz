package dev.fopwoc.julia.runtime;

import java.util.Set;

/** Immutable capability flags readable by game logic without entering the thread-owned graphics runtime. */
public final class CapabilitySnapshot implements AutoCloseable {
    private static volatile CapabilitySnapshot active;
    private final Thread owner = Thread.currentThread();
    private final Set<String> flags;

    private CapabilitySnapshot(Set<String> flags) {
        this.flags = Set.copyOf(flags);
    }

    public static synchronized CapabilitySnapshot publish(Set<String> flags) {
        if (active != null) throw new IllegalStateException("A capability snapshot is already published");
        var snapshot = new CapabilitySnapshot(flags);
        active = snapshot;
        return snapshot;
    }

    public static Set<String> current() {
        var snapshot = active;
        if (snapshot == null) {
            // Preserve lazy startup if the first graphics operation is a capability read.
            StartupHooks.ensure();
            snapshot = active;
        }
        if (snapshot == null)
            throw FatalGraphics.failure(
                    "Graphics capabilities are not initialized",
                    new IllegalStateException("Graphics capabilities are not initialized"));
        return snapshot.flags;
    }

    @Override
    public void close() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Capability publication must be closed by its owner");
        synchronized (CapabilitySnapshot.class) {
            if (active == this) active = null;
        }
    }
}
