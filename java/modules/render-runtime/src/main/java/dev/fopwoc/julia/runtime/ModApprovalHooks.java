package dev.fopwoc.julia.runtime;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/** JDK-only handoff after ZombieBuddy approval and before it publishes any mod classes. */
public final class ModApprovalHooks {
    public record Request(Path archive, String packageName, String approvedHash) {}

    private static volatile Consumer<Request> prepare;

    private ModApprovalHooks() {}

    public static synchronized void install(Consumer<Request> preparation) {
        if (prepare != null) throw new IllegalStateException("Mod preparation is already installed");
        prepare = Objects.requireNonNull(preparation);
    }

    public static void prepare(Path archive, String packageName, String approvedHash) {
        var preparation = prepare;
        if (preparation == null) throw new IllegalStateException("Julia mod preparation is unavailable");
        preparation.accept(new Request(archive, packageName, approvedHash));
    }
}
