package dev.fopwoc.julia.zomboid;

import dev.fopwoc.julia.runtime.ForeignWindow;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Runs on PZ's render context: isolated probe, then host GL dispatch restored and verified before returning. */
record RenderThreadProbe(ProbeMode mode, Path archive, Path cache, Path output) implements Runnable {
    private static final System.Logger LOG = System.getLogger(RenderThreadProbe.class.getName());
    private static final String LAUNCHER = "dev.fopwoc.julia.probe.ProbeLauncher";

    @Override
    public void run() {
        try {
            ClassLoader context = Thread.currentThread().getContextClassLoader();
            try (var hostGl = HostGlState.capture(Main.class.getClassLoader())) {
                try (var isolated = RuntimeLibraries.open(archive, cache)) {
                    Thread.currentThread().setContextClassLoader(isolated);
                    var launcher = Class.forName(LAUNCHER, true, isolated);
                    switch (mode) {
                        case ADAPTER ->
                            launcher.getMethod("runSuite", Path.class).invoke(null, output);
                        case WINDOW ->
                            launcher.getMethod("runWindowSuite", Path.class, ForeignWindow.class)
                                    .invoke(null, output, GameWindow.current());
                    }
                } finally {
                    Thread.currentThread().setContextClassLoader(context);
                }
                hostGl.restoreAndVerify();
            }
            Files.writeString(
                    output.resolve("host-gl-state.txt"),
                    "RESTORED: host GL capabilities republished; native binding query matches the pre-probe value\n");
            Status.write(
                    output,
                    "PASS: " + mode + " probe completed inside Project Zomboid; host GL dispatch restored;"
                            + " gameplay continuation unverified");
        } catch (Exception | LinkageError exception) {
            Throwable failure =
                    exception instanceof InvocationTargetException invocation ? invocation.getCause() : exception;
            LOG.log(System.Logger.Level.ERROR, "Julia probe failed", failure);
            try {
                Status.failure(output, failure);
            } catch (IOException writeFailure) {
                LOG.log(System.Logger.Level.ERROR, "Cannot write probe failure report", writeFailure);
            }
        }
    }
}
