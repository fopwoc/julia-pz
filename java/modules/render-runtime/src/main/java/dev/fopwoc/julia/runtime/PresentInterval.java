package dev.fopwoc.julia.runtime;

/**
 * The swap interval the game asked for. The game sets it through GLFW on whatever thread, possibly before the renderer
 * exists, and only the render thread may change the swapchain, so the value waits here for the next present.
 */
public final class PresentInterval {
    private static volatile int requested = -1;

    private PresentInterval() {}

    public static void set(int interval) {
        requested = interval;
    }

    /** Negative until the game has asked for anything. */
    public static int get() {
        return requested;
    }
}
