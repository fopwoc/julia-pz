package dev.fopwoc.julia.agent;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;

/**
 * Developer switch: {@code -Djulia.metalHud=true} shows Apple's Metal Performance HUD over the game. Metal reads
 * {@code MTL_HUD_ENABLED} when it first initializes, so the agent sets it before the game creates any graphics.
 */
final class MetalHud {
    private static final System.Logger LOG = System.getLogger(MetalHud.class.getName());

    private MetalHud() {}

    // setenv is a restricted native call by design; the switch is opt-in and failures are only logged.
    @SuppressWarnings("restricted")
    static void enableIfRequested() {
        if (!Boolean.getBoolean("julia.metalHud")
                || !System.getProperty("os.name").startsWith("Mac")) return;
        var linker = Linker.nativeLinker();
        try (var arena = Arena.ofConfined()) {
            var setenv = linker.downcallHandle(
                    linker.defaultLookup().find("setenv").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
            int result = (int) setenv.invokeExact(arena.allocateFrom("MTL_HUD_ENABLED"), arena.allocateFrom("1"), 1);
            if (result != 0) throw new IllegalStateException("setenv returned " + result);
            LOG.log(System.Logger.Level.INFO, "Metal Performance HUD enabled");
        } catch (Throwable failure) {
            // A developer overlay must never stop the game.
            LOG.log(System.Logger.Level.WARNING, "Cannot enable the Metal Performance HUD", failure);
        }
    }
}
