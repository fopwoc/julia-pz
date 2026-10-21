package dev.fopwoc.julia.backend.sdl;

import dev.fopwoc.julia.backend.utils.Sdl;
import org.lwjgl.system.JNI;

/** Developer switch: {@code -Djulia.metalHud=true} shows Apple's Metal Performance HUD over the game window. */
public final class SdlDeveloperHud {
    private static final System.Logger LOG = System.getLogger(SdlDeveloperHud.class.getName());

    private SdlDeveloperHud() {}

    public static void prepareIfRequested() {
        if (!Boolean.getBoolean("julia.metalHud")) return;
        try {
            if (!JNI.invokeZ(SdlExtension.symbol("JULIA_SDL_PrepareDeveloperHud")))
                throw Sdl.failure("Enable developer HUD runtime");
            LOG.log(System.Logger.Level.INFO, "Metal Performance HUD runtime enabled for this process");
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "Cannot enable the Metal Performance HUD runtime", failure);
        }
    }

    public static void showIfRequested(long device, long window) {
        if (!Boolean.getBoolean("julia.metalHud")) return;
        try {
            if (!JNI.invokePPZ(device, window, SdlExtension.symbol("JULIA_SDL_ShowDeveloperHud")))
                throw Sdl.failure("Show developer HUD");
            LOG.log(System.Logger.Level.INFO, "Metal Performance HUD layer configured");
        } catch (RuntimeException failure) {
            // A developer overlay must never stop the game.
            LOG.log(System.Logger.Level.WARNING, "Cannot show the Metal Performance HUD", failure);
        }
    }
}
