package dev.fopwoc.julia.backend.runtime;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.backend.RenderBackend;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMInitConfig;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPURenderBackend;
import com.gtnewhorizons.angelica.sdlgpu.device.Device;
import dev.fopwoc.julia.backend.HostedBackend;
import dev.fopwoc.julia.runtime.CapabilitySnapshot;
import java.util.function.Supplier;
import me.eigenraven.lwjgl3ify.api.GLCapabilitiesOverride;

/** GLSM running on Julia's hosted SDL backend, from device creation to backend shutdown. */
public final class GlsmSession implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(GlsmSession.class.getName());

    /** Drawable size in pixels; bounded so readback buffers stay addressable. */
    public record Size(int width, int height) {
        public Size {
            if (width <= 0 || height <= 0 || (long) width * height > 16_777_216) {
                throw new IllegalArgumentException("Unsupported drawable size: " + width + "x" + height);
            }
        }

        public long pixels() {
            return (long) width * height;
        }
    }

    private final Device device;
    private RenderBackend backend;
    private Size size;
    private CapabilitySnapshot capabilities;

    private GlsmSession(Device device) {
        this.device = device;
    }

    /**
     * Creates the device and initializes GLSM.
     *
     * @param window SDL window to present into, or {@code 0} for offscreen rendering
     * @param size drawable size, read once the backend has attached to the window
     */
    public static GlsmSession open(HostedVideo video, long window, Supplier<Size> size, String label) {
        var session = new GlsmSession(SDLGPUGate.device());
        try {
            session.start(video, window, size, label);
            return session;
        } catch (RuntimeException | Error failure) {
            try {
                session.close();
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private void start(HostedVideo video, long window, Supplier<Size> drawable, String label) {
        String driver = video.platform().gpuBackend().driverName();
        if (!device.createDevice()) throw new IllegalStateException("Cannot create the SDL GPU device");
        if (!driver.equalsIgnoreCase(device.getDriverName())) {
            throw new IllegalStateException("Expected " + driver + " device, got " + device.getDriverName());
        }
        LOG.log(System.Logger.Level.INFO, "Created {0} device on {1}", device.getDriverName(), device.getDeviceName());
        if (window != 0) device.claimWindow(window);
        backend = BackendManager.RENDER_BACKEND;
        if (!(backend instanceof HostedBackend))
            throw new IllegalStateException("Unexpected backend: " + backend.getName());
        backend.onPostWindowCreate(window);
        GLCapabilitiesOverride.set(SDLGPURenderBackend.advertisedCapabilities());
        size = drawable.get();
        GLStateManager.initialize(GLSMInitConfig.builder()
                .displaySize(size.width(), size.height())
                .build());
        GLStateManager.setRunningSplash(false);
        GLStateManager.markSplashComplete(label);
        capabilities = CapabilitySnapshot.publish(SDLGPURenderBackend.advertisedCapabilities());
        LOG.log(
                System.Logger.Level.INFO,
                "GLSM ready on {0} at {1}x{2} ({3})",
                backend.getName(),
                size.width(),
                size.height(),
                window == 0 ? "offscreen" : "windowed");
    }

    public Device device() {
        return device;
    }

    public RenderBackend backend() {
        return backend;
    }

    public Size size() {
        return size;
    }

    @Override
    public void close() {
        // Once the backend exists it owns the device and its shutdown destroys both.
        try {
            if (backend != null) BackendManager.shutdown();
            else device.destroyDevice();
        } finally {
            if (capabilities != null) capabilities.close();
        }
    }
}
