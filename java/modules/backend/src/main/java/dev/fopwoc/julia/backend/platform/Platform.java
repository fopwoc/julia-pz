package dev.fopwoc.julia.backend.platform;

import dev.fopwoc.julia.backend.gpu.GpuBackend;
import java.util.Locale;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.JNI;
import org.lwjgl.system.macosx.LibSystem;

/** Host operating system facts the graphics runtime depends on: GPU driver choice and video-thread rules. */
public enum Platform {
    MACOS(GpuBackend.METAL) {
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
    // Planned ports; their window bindings and native builds are not implemented or verified yet.
    WINDOWS(GpuBackend.DIRECT3D12),
    LINUX(GpuBackend.VULKAN);

    private final GpuBackend gpuBackend;

    Platform(GpuBackend gpuBackend) {
        this.gpuBackend = gpuBackend;
    }

    public static Platform current() {
        String name = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (name.startsWith("mac")) return MACOS;
        if (name.startsWith("windows")) return WINDOWS;
        if (name.startsWith("linux")) return LINUX;
        throw new UnsupportedOperationException("Unsupported operating system: " + name);
    }

    /** The GPU backend requested from Angelica and expected from the created device. */
    public GpuBackend gpuBackend() {
        return gpuBackend;
    }

    /** Fails fast where the platform restricts which thread may initialize SDL video. */
    public void requireVideoThread() {}

    /** Every platform loads Julia's patched SDL build, never LWJGL's stock library. */
    public void configureNativeLibraries() {
        Configuration.SDL_LIBRARY_NAME.set("SDL3Julia");
    }

    public void requireImplemented() {
        if (this != MACOS) throw new UnsupportedOperationException("The graphics runtime is not yet ported to " + this);
        gpuBackend.requireImplemented();
    }
}
