package dev.fopwoc.julia.backend.sdl;

import dev.fopwoc.julia.backend.utils.Sdl;
import org.lwjgl.system.JNI;

/** Developer switch: {@code -Djulia.metalHud=true} shows Apple's Metal Performance HUD over the game window. */
public final class SdlDeveloperHud {
    private static final System.Logger LOG = System.getLogger(SdlDeveloperHud.class.getName());

    private SdlDeveloperHud() {}

    public static void showIfRequested(long device, long window) {
        if (!Boolean.getBoolean("julia.metalHud")) return;
        try {
            if (!JNI.invokePPZ(device, window, SdlExtension.symbol("JULIA_SDL_ShowDeveloperHud")))
                throw Sdl.failure("Show developer HUD");
            LOG.log(System.Logger.Level.INFO, "Metal Performance HUD enabled");
        } catch (RuntimeException failure) {
            // A developer overlay must never stop the game.
            LOG.log(System.Logger.Level.WARNING, "Cannot show the Metal Performance HUD", failure);
        }
    }
}
