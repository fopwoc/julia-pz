package dev.fopwoc.julia.probe;

import dev.fopwoc.julia.host.IsolatedRuntime;
import dev.fopwoc.julia.runtime.ForeignWindow;
import dev.fopwoc.julia.runtime.RenderSession;
import java.lang.invoke.MethodType;
import java.nio.file.Path;

/**
 * Public entry points that run probes inside an isolated graphics runtime, from the CLI or from the game mod.
 *
 * <p>Arguments are JDK or {@code dev.fopwoc.julia.runtime} types only, so callers in other class loaders can use them.
 */
public final class ProbeLauncher {
    private static final String SCENES = "dev.fopwoc.julia.probe.scene.";
    // Probes own their frame boundaries and backend cleanup; injected game-frame hooks are tested separately.
    private static final RenderSession.Frames SELF_MANAGED = new RenderSession.Frames() {
        @Override
        public void begin() {
            throw new IllegalStateException("Probe owns its frame lifecycle");
        }

        @Override
        public void present() {
            throw new IllegalStateException("Probe owns its frame lifecycle");
        }

        @Override
        public void abort() {}

        @Override
        public void close() {}
    };
    private static final MethodType OUTPUT = MethodType.methodType(void.class, Path.class);

    private ProbeLauncher() {}

    /** Full GLSM adapter over the offscreen Metal backend. */
    public static void runAdapter(Path output) throws Exception {
        runtime().invoke("dev.fopwoc.julia.probe.adapter.AdapterProbe", "run", OUTPUT, output);
    }

    /** Raw-device baseline followed by the full adapter. */
    public static void runSuite(Path output) throws Exception {
        runtime().invoke("dev.fopwoc.julia.probe.ProbeSuite", "run", OUTPUT, output);
    }

    /** Raw-device baseline followed by presentation into a game-owned window. */
    public static void runWindowSuite(Path output, ForeignWindow window) throws Exception {
        runtime()
                .invoke(
                        "dev.fopwoc.julia.probe.WindowSuite",
                        "run",
                        MethodType.methodType(void.class, Path.class, ForeignWindow.class),
                        output,
                        window);
    }

    private static IsolatedRuntime runtime() {
        return new IsolatedRuntime(SCENES, SELF_MANAGED);
    }
}
