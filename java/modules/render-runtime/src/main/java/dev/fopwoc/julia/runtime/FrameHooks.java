package dev.fopwoc.julia.runtime;

/** Injected frame boundaries; host window/input calls remain owned by the game. */
public final class FrameHooks {
    private FrameHooks() {}

    public static void begin() {
        try {
            RenderSession.current().begin();
        } catch (Throwable failure) {
            throw FatalGraphics.failure("Cannot begin render frame", failure);
        }
    }

    /** The game may render at a different resolution from its native window during startup. */
    public static void begin(int width, int height) {
        try {
            RenderSession.current().begin(width, height);
        } catch (Throwable failure) {
            throw FatalGraphics.failure("Cannot begin sized render frame", failure);
        }
    }

    public static void present() {
        try {
            RenderSession.current().present();
        } catch (Throwable failure) {
            throw FatalGraphics.failure("Cannot present render frame", failure);
        }
    }

    public static Throwable abort(Throwable failure) {
        FatalGraphics.failure("Render frame failed", failure);
        return RenderSession.abort(failure);
    }
}
