package dev.fopwoc.julia.probe;

import dev.fopwoc.julia.host.IsolatedRuntime;
import dev.fopwoc.julia.runtime.RenderSession;
import java.lang.invoke.MethodType;
import java.nio.file.Path;

/**
 * Public entry points that run probes inside an isolated graphics runtime from the CLI.
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

    /** Focused vertex-input checks on the explicitly selected GPU backend. */
    public static void runVertices(Path output) throws Exception {
        runtime().invoke("dev.fopwoc.julia.probe.adapter.VertexProbe", "run", OUTPUT, output);
    }

    /** Prepared ImGui renderer and JNI from a supplied archive, with no window or game entry point. */
    public static void runImGui(Path output, Path archive, Path natives) throws Exception {
        runtime()
                .invoke(
                        "dev.fopwoc.julia.probe.imgui.ImGuiProbe",
                        "run",
                        MethodType.methodType(void.class, Path.class, Path.class, Path.class),
                        output,
                        archive,
                        natives);
    }

    /** Raw-device baseline followed by the full adapter. */
    public static void runSuite(Path output) throws Exception {
        runtime().invoke("dev.fopwoc.julia.probe.ProbeSuite", "run", OUTPUT, output);
    }

    private static IsolatedRuntime runtime() {
        return new IsolatedRuntime(SCENES, SELF_MANAGED);
    }
}
