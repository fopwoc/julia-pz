package dev.fopwoc.julia.runtime;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;

/** JDK-only handoff after ZombieBuddy approval and before it publishes any mod classes. */
public final class ModApprovalHooks {
    public record Request(Path archive, String packageName, String approvedHash) {}

    private static volatile Function<Request, Boolean> prepare;

    private ModApprovalHooks() {}

    public static synchronized void install(Function<Request, Boolean> gate) {
        if (prepare != null) throw new IllegalStateException("Mod preparation is already installed");
        prepare = Objects.requireNonNull(gate);
    }

    public static boolean prepare(Path archive, String packageName, String approvedHash) {
        var gate = prepare;
        if (gate == null) throw new IllegalStateException("Julia mod preparation is unavailable");
        return gate.apply(new Request(archive, packageName, approvedHash));
    }
}
