package dev.fopwoc.julia.backend.platform;

import java.util.Locale;
import org.lwjgl.system.JNI;
import org.lwjgl.system.macosx.LibSystem;

/** Host operating system facts the graphics runtime depends on: GPU driver and video-thread rules. */
public enum Platform {
    MACOS("metal") {
        @Override
        public void requireVideoThread() {
            // Cocoa only permits SDL video initialization on the process main thread.
            long isMainThread = LibSystem.getLibrary().getFunctionAddress("pthread_main_np");
            if (isMainThread == 0 || JNI.invokeI(isMainThread) == 0) {
                throw new IllegalStateException(
                        "SDL video initialization requires the Cocoa main thread; current thread: "
                                + Thread.currentThread().getName());
            }
        }
    },
    // Driver choices for the planned ports; their runtime paths are not implemented or verified yet.
    WINDOWS("direct3d12"),
    LINUX("vulkan");

    private final String gpuDriver;

    Platform(String gpuDriver) {
        this.gpuDriver = gpuDriver;
    }

    public static Platform current() {
        String name = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (name.startsWith("mac")) return MACOS;
        if (name.startsWith("windows")) return WINDOWS;
        if (name.startsWith("linux")) return LINUX;
        throw new UnsupportedOperationException("Unsupported operating system: " + name);
    }

    /** SDL GPU driver name requested from Angelica's backend options and expected from the created device. */
    public String gpuDriver() {
        return gpuDriver;
    }

    /** Fails fast where the platform restricts which thread may initialize SDL video. */
    public void requireVideoThread() {}

    public void requireImplemented() {
        if (this != MACOS) throw new UnsupportedOperationException("The graphics runtime is not yet ported to " + this);
    }
}
