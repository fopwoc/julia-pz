package dev.fopwoc.julia.backend.runtime;

import com.gtnewhorizons.angelica.glsm.backend.BackendOptions;
import dev.fopwoc.julia.backend.platform.Platform;
import dev.fopwoc.julia.backend.utils.Sdl;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.system.Configuration;

/**
 * SDL video for the hosted backend. Closing it also releases the isolated GL dispatch, so the host's native GL state
 * is left as it was before the runtime started.
 */
public final class HostedVideo implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(HostedVideo.class.getName());
    private final Platform platform;

    private HostedVideo(Platform platform) {
        this.platform = platform;
    }

    public static HostedVideo start() {
        var platform = Platform.current();
        platform.requireImplemented();
        platform.configureNativeLibraries();
        Configuration.OPENGL_EXPLICIT_INIT.set(true);
        BackendOptions.latch(true, platform.gpuBackend().driverName(), 0);
        Sdl.require(SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO), "Initialize SDL video");
        LOG.log(
                System.Logger.Level.INFO,
                "SDL video initialized for {0} ({1})",
                platform,
                platform.gpuBackend().driverName());
        return new HostedVideo(platform);
    }

    public Platform platform() {
        return platform;
    }

    @Override
    public void close() {
        try {
            GlDispatch.release();
        } finally {
            SDLInit.SDL_Quit();
            LOG.log(System.Logger.Level.INFO, "SDL video shut down; isolated GL dispatch released");
        }
    }
}
