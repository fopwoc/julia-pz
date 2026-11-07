package dev.fopwoc.julia.profiling.agent;

import dev.fopwoc.julia.profiling.core.CaptureSession;

/** Hot-path timing only; control polling and report writing live on the controller thread. */
public final class ProfilingHooks {
    private static volatile CaptureSession capture;
    private static volatile Automation automation;
    // Written only by the render thread, so a plain increment is safe. Only the status line reads it.
    private static volatile long frames;
    private static final int GL_RENDERER = 0x1F01;
    private static volatile String nativeGpu = "unknown";
    private static boolean readGpu;
    private static volatile long renderThread;
    private static volatile long logicThread;
    private static final ThreadLocal<long[]> START = ThreadLocal.withInitial(() -> new long[1]);

    private ProfilingHooks() {}

    static void capture(CaptureSession session) {
        capture = session;
    }

    static void automation(Automation driver) {
        automation = driver;
    }

    /** Only for native GL runs of the game: LWJGL aborts the JVM on a call without a current context. */
    static void readNativeGpuOnNextFrame() {
        readGpu = true;
    }

    static long renderThread() {
        return renderThread;
    }

    static long logicThread() {
        return logicThread;
    }

    public static void tick() {
        logicThread = Thread.currentThread().threadId();
        var driver = automation;
        if (driver != null) driver.tick(System.nanoTime());
    }

    public static boolean inputBlocked() {
        return automation != null;
    }

    public static void overlay() {
        var driver = automation;
        if (driver != null) driver.overlay(System.nanoTime(), frames);
    }

    public static void begin() {
        var session = capture;
        if (session != null && session.active()) START.get()[0] = System.nanoTime();
    }

    /** Julia reports its device; native GL only answers on the thread that owns the context, which is this one. */
    static String gpuName() {
        var device = System.getProperty("julia.renderer.device");
        return device != null ? device : nativeGpu;
    }

    private static void readNativeGpu() {
        try {
            var gl = Class.forName("org.lwjgl.opengl.GL11", true, ClassLoader.getSystemClassLoader());
            var name = gl.getMethod("glGetString", int.class).invoke(null, GL_RENDERER);
            if (name != null) nativeGpu = name.toString();
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            nativeGpu = "unknown";
        }
    }

    public static void complete(boolean rendered) {
        if (rendered) frames++;
        renderThread = Thread.currentThread().threadId();
        if (rendered && readGpu) {
            readGpu = false;
            readNativeGpu();
        }
        var session = capture;
        if (rendered && session != null && session.active()) {
            long now = System.nanoTime();
            session.frame(now, Math.max(0, now - START.get()[0]));
        }
    }
}
