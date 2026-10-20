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

    /** The one-line status shown in PZ's main menu and in ZombieBuddy's mod approval window. */
    public static String label() {
        return switch (getBackend()) {
            case "" -> "Julia inactive";
            case "metal" -> "Julia active | Metal";
            case "vulkan" -> "Julia active | Vulkan";
            case "direct3d12" -> "Julia active | Direct3D 12";
            case String other -> "Julia active | " + other;
        };
    }
}
