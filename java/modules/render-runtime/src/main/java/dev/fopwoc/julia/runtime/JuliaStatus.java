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

    /** The agent jar's Git-derived build version; {@code dev} when running from classes outside the jar. */
    public static String getVersion() {
        var version = JuliaStatus.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    /** The one-line status shown in PZ's main menu and in ZombieBuddy's mod approval window. */
    public static String label() {
        var state = switch (getBackend()) {
            case "" -> "inactive";
            case "metal" -> "active | Metal";
            case "vulkan" -> "active | Vulkan";
            case "direct3d12" -> "active | Direct3D 12";
            case String other -> "active | " + other;
        };
        return "Julia " + getVersion() + " " + state;
    }
}
