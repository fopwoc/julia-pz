package dev.fopwoc.julia.zomboid;

import dev.fopwoc.julia.utils.logging.LogConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/** ZombieBuddy entry point. Schedules the probe on PZ's render thread; the probe never shares LWJGL with the game. */
public final class Main {
    private static final System.Logger LOG = System.getLogger(Main.class.getName());
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private Main() {}

    public static void main(String[] args) {
        if (!STARTED.compareAndSet(false, true)) return;
        LogConfiguration.configure();
        try {
            var mode = ProbeMode.fromSystemProperty();
            Path archive = ModPaths.archive();
            Path root = ModPaths.outputRoot();
            Path output = root.resolve("run-" + ProcessHandle.current().pid());
            Files.createDirectories(output);
            Status.write(output, "Julia loaded by ZombieBuddy; scheduling render-context probe");
            var probe = new RenderThreadProbe(mode, archive, root.resolve("runtime"), output);
            Class.forName("zombie.core.opengl.RenderThread")
                    .getMethod("queueInvokeOnRenderContext", Runnable.class)
                    .invoke(null, probe);
            LOG.log(System.Logger.Level.INFO, "Julia {0} probe queued on the render context; output={1}", mode, output);
        } catch (Exception failure) {
            LOG.log(System.Logger.Level.ERROR, "Cannot start the Julia probe", failure);
        }
    }
}
