package dev.fopwoc.julia.runtime;

/** Read-only Lua-facing status for this JVM; an armed agent is not yet an active renderer. */
public final class JuliaStatus {
    private JuliaStatus() {}

    public static boolean isActive() {
        return !getBackend().isEmpty();
    }

    public static String getBackend() {
        return System.getProperty("julia.renderer.backend", "");
    }
}
