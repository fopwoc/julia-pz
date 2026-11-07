package dev.fopwoc.julia.profiling.agent;

import dev.fopwoc.julia.launch.Launcher;
import dev.fopwoc.julia.profiling.run.Scene;
import dev.fopwoc.julia.utils.logging.LogConfiguration;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;

/**
 * Measurement entry, given {@code <renderer>,<run>}: {@code opengl} intercepts no GL at all, {@code julia} also starts
 * Julia. Both sides of a comparison use this one jar. A {@code scene.json} in the run folder makes it drive the game
 * through that scene; without one it only observes.
 */
public final class ProfilerAgent {
    private static final System.Logger LOG = System.getLogger(ProfilerAgent.class.getName());

    private ProfilerAgent() {}

    public static void premain(String arguments, Instrumentation instrumentation) throws Exception {
        LogConfiguration.configure();
        if (arguments == null || arguments.isBlank())
            throw new IllegalArgumentException("Supply a fresh profiling run directory");
        int comma = arguments.indexOf(',');
        String renderer = comma < 0 ? "" : arguments.substring(0, comma);
        if (!renderer.equals("opengl") && !renderer.equals("julia"))
            throw new IllegalArgumentException("Supply opengl,<run> or julia,<run>");
        boolean julia = renderer.equals("julia");
        Path run = Path.of(arguments.substring(comma + 1));
        Path sceneFile = run.resolve("scene.json");
        boolean automated = Files.isRegularFile(sceneFile);
        for (Class<?> loaded : instrumentation.getAllLoadedClasses())
            if (loaded.getName().equals(FrameInstrumentation.TARGET.replace('/', '.'))
                    || automated
                            && (loaded.getName().equals(LoadingInstrumentation.TARGET.replace('/', '.'))
                                    || AutomationInstrumentation.targets(
                                            loaded.getName().replace('.', '/'))))
                throw new IllegalStateException("Profiling target loaded before observer installation");
        var controller = new CaptureController(run);
        if (automated) {
            ProfilingHooks.automation(new Automation(
                    Scene.read(sceneFile),
                    new ReflectiveGame(),
                    controller,
                    run.resolve("automation.txt"),
                    julia,
                    () -> System.getProperty("julia.renderer.backend"),
                    System.getProperty("profile.label", renderer),
                    ProfilingHooks::gpuName));
            if (!julia) ProfilingHooks.readNativeGpuOnNextFrame();
        }
        // Retransformation-capable observers follow the incapable transformers used by Julia's prepared classes.
        instrumentation.addTransformer(
                new ClassFileTransformer() {
                    @Override
                    public byte[] transform(
                            ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
                        boolean loading = automated && LoadingInstrumentation.TARGET.equals(name);
                        boolean logic = automated && AutomationInstrumentation.targets(name);
                        if (!loading && !logic && !FrameInstrumentation.TARGET.equals(name)) return null;
                        try {
                            if (logic) return AutomationInstrumentation.instrument(bytes, loader);
                            if (loading) {
                                var instrumented = LoadingInstrumentation.instrument(bytes, loader);
                                Files.writeString(run.resolve("loading-hook.txt"), "READY\n");
                                return instrumented;
                            }
                            var instrumented = FrameInstrumentation.instrument(bytes, loader);
                            controller.frameOwnerReady();
                            return instrumented;
                        } catch (RuntimeException | IOException failure) {
                            LOG.log(
                                    System.Logger.Level.ERROR,
                                    "Profiling hook could not be installed; measurement is invalid",
                                    failure);
                            try {
                                controller.status("ERROR " + failure);
                                if (loading) Files.writeString(run.resolve("loading-hook.txt"), "ERROR " + failure);
                            } catch (IOException report) {
                                failure.addSuppressed(report);
                            }
                            return null;
                        }
                    }
                },
                true);
        controller.start();
        Runtime.getRuntime()
                .addShutdownHook(new Thread(
                        () -> {
                            try {
                                controller.close();
                            } catch (IOException failure) {
                                LOG.log(System.Logger.Level.ERROR, "Cannot finish profiling recording", failure);
                            }
                        },
                        "profiling-shutdown"));
        LOG.log(System.Logger.Level.INFO, "Profiling observer armed at {0}", run);
        if (julia) {
            // Added after the observer, whose retransformation-capable hook still runs on Julia's rewritten bytes.
            var archive = Path.of(ProfilerAgent.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            Launcher.activate("activate=" + run.resolve("renderer"), instrumentation, archive);
        }
    }
}
