package dev.julia.runtime;

/** Injected frame boundaries; host window/input calls remain owned by the game. */
public final class FrameHooks {
    private FrameHooks() {}

    public static void begin() {
        RenderSession.current().begin();
    }

    public static void present() {
        RenderSession.current().present();
    }

    public static Throwable abort(Throwable failure) {
        return RenderSession.abort(failure);
    }
}
