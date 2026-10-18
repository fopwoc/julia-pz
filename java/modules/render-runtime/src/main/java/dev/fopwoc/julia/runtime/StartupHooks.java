package dev.fopwoc.julia.runtime;

import java.util.Objects;
import java.util.function.Supplier;

/** Process startup is installed before consumers load, but native graphics are initialized on their owning thread. */
public final class StartupHooks {
    private static Supplier<? extends AutoCloseable> initializer;
    private static AutoCloseable runtime;
    private static Thread owner;
    private static Throwable failure;
    private static boolean starting;

    private StartupHooks() {}

    public static synchronized void install(Supplier<? extends AutoCloseable> start) {
        if (initializer != null) throw new IllegalStateException("Startup is already installed");
        initializer = Objects.requireNonNull(start);
    }

    static synchronized void ensure() {
        if (initializer == null) return;
        if (failure != null) throw new IllegalStateException("Graphics startup failed", failure);
        if (owner != null && owner != Thread.currentThread())
            throw new IllegalStateException("Graphics runtime belongs to " + owner.getName() + "; call arrived on "
                    + Thread.currentThread().getName());
        if (runtime != null) return;
        if (starting) throw new IllegalStateException("Recursive graphics startup");
        owner = Thread.currentThread();
        starting = true;
        try {
            runtime = Objects.requireNonNull(initializer.get());
        } catch (RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            starting = false;
        }
    }

    public static synchronized void close() {
        if (owner != null && owner != Thread.currentThread())
            throw new IllegalStateException("Graphics shutdown must run on " + owner.getName());
        try {
            if (runtime != null) runtime.close();
        } catch (RuntimeException | Error error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Graphics shutdown failed", error);
        } finally {
            runtime = null;
            initializer = null;
            owner = null;
            failure = null;
        }
    }
}
