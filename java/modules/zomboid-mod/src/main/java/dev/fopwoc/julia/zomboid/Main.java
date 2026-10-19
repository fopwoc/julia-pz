package dev.fopwoc.julia.zomboid;

import dev.fopwoc.julia.utils.logging.LogConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/** ZombieBuddy entry point: reports the early renderer, with probes available only by explicit request. */
public final class Main {
    private static final System.Logger LOG = System.getLogger(Main.class.getName());
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private Main() {}

    public static void main(String[] args) {
        if (!STARTED.compareAndSet(false, true)) return;
        LogConfiguration.configure();
        if ("active".equals(System.getProperty("julia.startup"))) {
            LOG.log(System.Logger.Level.INFO, "Julia renderer is active through the startup agent");
            return;
        }
        if (System.getProperty("julia.probe") == null) {
            LOG.log(System.Logger.Level.ERROR, "Julia requires -javaagent:julia-agent.jar=activate before ZombieBuddy");
            return;
        }
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
