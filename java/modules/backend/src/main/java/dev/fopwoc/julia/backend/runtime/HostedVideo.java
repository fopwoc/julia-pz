package dev.fopwoc.julia.backend.runtime;

import com.gtnewhorizons.angelica.glsm.backend.BackendOptions;
import dev.fopwoc.julia.backend.platform.Platform;
import dev.fopwoc.julia.backend.utils.Sdl;
import dev.fopwoc.julia.runtime.ForeignWindow;
import org.lwjgl.sdl.SDLHints;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.Configuration;

/**
 * SDL video for the hosted backend. Closing it also releases the isolated GL dispatch, so the host's native GL state
 * is left as it was before the runtime started.
 */
public final class HostedVideo implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(HostedVideo.class.getName());
    private final Platform platform;
    private boolean importedDisplay;

    private HostedVideo(Platform platform) {
        this.platform = platform;
    }

    public static HostedVideo start() {
        return start(null);
    }

    public static HostedVideo start(ForeignWindow window) {
        var platform = Platform.current();
        platform.configureNativeLibraries();
        Configuration.OPENGL_EXPLICIT_INIT.set(true);
        BackendOptions.latch(true, platform.gpuBackend().driverName(), 0);
        var video = new HostedVideo(platform);
        if (window instanceof ForeignWindow.X11 || window instanceof ForeignWindow.Wayland) {
            String driver = window instanceof ForeignWindow.Wayland ? "wayland" : "x11";
            Sdl.require(
                    SDLHints.SDL_SetHintWithPriority(
                            SDLHints.SDL_HINT_VIDEO_DRIVER, driver, SDLHints.SDL_HINT_OVERRIDE),
                    "Select GLFW's Linux window system");
            if (window instanceof ForeignWindow.X11)
                Sdl.require(
                        SDLHints.SDL_SetHint(SDLHints.SDL_HINT_VIDEO_X11_EXTERNAL_WINDOW_INPUT, "0"),
                        "Keep GLFW X11 input ownership");
        }
        if (window instanceof ForeignWindow.Wayland wayland) {
            Sdl.require(
                    SDLProperties.SDL_SetPointerProperty(
                            SDLProperties.SDL_GetGlobalProperties(),
                            SDLVideo.SDL_PROP_GLOBAL_VIDEO_WAYLAND_WL_DISPLAY_POINTER,
                            wayland.display()),
                    "Import GLFW Wayland display");
            video.importedDisplay = true;
        }
        try {
            Sdl.require(SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO), "Initialize SDL video");
        } catch (RuntimeException | Error failure) {
            video.close();
            throw failure;
        }
        LOG.log(
                System.Logger.Level.INFO,
                "SDL video initialized for {0} ({1})",
                platform,
                platform.gpuBackend().driverName());
        return video;
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
            if (importedDisplay) {
                SDLProperties.SDL_ClearProperty(
                        SDLProperties.SDL_GetGlobalProperties(),
                        SDLVideo.SDL_PROP_GLOBAL_VIDEO_WAYLAND_WL_DISPLAY_POINTER);
                importedDisplay = false;
            }
            LOG.log(System.Logger.Level.INFO, "SDL video shut down; isolated GL dispatch released");
        }
    }
}
