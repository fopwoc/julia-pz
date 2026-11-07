package dev.fopwoc.julia.profiling.agent;

import dev.fopwoc.julia.launch.Launcher;
import dev.fopwoc.julia.utils.logging.LogConfiguration;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;

/**
 * Measurement entry: {@code <run>} observes the native renderer, {@code julia=<run>} also starts Julia. Both sides of a
 * comparison use this one jar. The observer itself touches no GL and works whichever renderer is below it.
 */
public final class ProfilerAgent {
    private static final System.Logger LOG = System.getLogger(ProfilerAgent.class.getName());

    private static final String JULIA_PREFIX = "julia=";

    private ProfilerAgent() {}

    public static void premain(String arguments, Instrumentation instrumentation) throws Exception {
        LogConfiguration.configure();
        if (arguments == null || arguments.isBlank())
            throw new IllegalArgumentException("Supply a fresh profiling run directory");
        boolean julia = arguments.startsWith(JULIA_PREFIX);
        Path run = Path.of(julia ? arguments.substring(JULIA_PREFIX.length()) : arguments);
        boolean automated = Files.isRegularFile(run.resolve("scenario.json"));
        for (Class<?> loaded : instrumentation.getAllLoadedClasses())
            if (loaded.getName().equals(FrameInstrumentation.TARGET.replace('/', '.'))
                    || automated && loaded.getName().equals(LoadingInstrumentation.TARGET.replace('/', '.')))
                throw new IllegalStateException("Profiling target loaded before observer installation");
        var controller = new CaptureController(run);
        // Retransformation-capable observers follow the incapable transformers used by Julia's prepared classes.
        instrumentation.addTransformer(
                new ClassFileTransformer() {
                    @Override
                    public byte[] transform(
                            ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
                        boolean loading = automated && LoadingInstrumentation.TARGET.equals(name);
                        if (!loading && !FrameInstrumentation.TARGET.equals(name)) return null;
                        try {
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
