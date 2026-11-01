package dev.fopwoc.julia.profiling.agent;

import dev.fopwoc.julia.profiling.core.CaptureSession;

/** Hot-path timing only; control polling and report writing live on the controller thread. */
public final class ProfilingHooks {
    private static volatile CaptureSession capture;
    private static final ThreadLocal<long[]> START = ThreadLocal.withInitial(() -> new long[1]);

    private ProfilingHooks() {}

    static void capture(CaptureSession session) {
        capture = session;
    }

    public static void begin() {
        var session = capture;
        if (session != null && session.active()) START.get()[0] = System.nanoTime();
    }

    public static void complete(boolean rendered) {
        var session = capture;
        if (rendered && session != null && session.active()) {
            long now = System.nanoTime();
            session.frame(now, Math.max(0, now - START.get()[0]));
        }
    }
}
