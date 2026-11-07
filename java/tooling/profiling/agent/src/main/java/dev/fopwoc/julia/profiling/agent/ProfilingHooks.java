package dev.fopwoc.julia.profiling.agent;

import dev.fopwoc.julia.profiling.core.CaptureSession;

/** Hot-path timing only; control polling and report writing live on the controller thread. */
public final class ProfilingHooks {
    private static volatile CaptureSession capture;
    private static volatile Automation automation;
    // Written only by the render thread, so a plain increment is safe. Only the status line reads it.
    private static volatile long frames;
    private static final ThreadLocal<long[]> START = ThreadLocal.withInitial(() -> new long[1]);

    private ProfilingHooks() {}

    static void capture(CaptureSession session) {
        capture = session;
    }

    static void automation(Automation driver) {
        automation = driver;
    }

    public static void tick() {
        var driver = automation;
        if (driver != null) driver.tick(System.nanoTime());
    }

    public static void overlay() {
        var driver = automation;
        if (driver != null) driver.overlay(System.nanoTime(), frames);
    }

    public static void begin() {
        var session = capture;
        if (session != null && session.active()) START.get()[0] = System.nanoTime();
    }

    public static void complete(boolean rendered) {
        if (rendered) frames++;
        var session = capture;
        if (rendered && session != null && session.active()) {
            long now = System.nanoTime();
            session.frame(now, Math.max(0, now - START.get()[0]));
        }
    }
}
