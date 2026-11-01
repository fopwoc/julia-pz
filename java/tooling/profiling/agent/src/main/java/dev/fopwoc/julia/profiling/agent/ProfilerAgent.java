package dev.fopwoc.julia.profiling.agent;

import dev.fopwoc.julia.utils.logging.LogConfiguration;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;

/** Standalone observer: no dependency on Julia, Angelica, ZombieBuddy, or a native graphics API. */
public final class ProfilerAgent {
    private static final System.Logger LOG = System.getLogger(ProfilerAgent.class.getName());

    private ProfilerAgent() {}

    public static void premain(String arguments, Instrumentation instrumentation) throws Exception {
        LogConfiguration.configure();
        if (arguments == null || arguments.isBlank())
            throw new IllegalArgumentException("Supply a fresh profiling run directory");
        boolean automated = Files.isRegularFile(Path.of(arguments).resolve("scenario.json"));
        for (Class<?> loaded : instrumentation.getAllLoadedClasses())
            if (loaded.getName().equals(FrameInstrumentation.TARGET.replace('/', '.'))
                    || automated && loaded.getName().equals(LoadingInstrumentation.TARGET.replace('/', '.')))
                throw new IllegalStateException("Profiling target loaded before observer installation");
        var controller = new CaptureController(Path.of(arguments));
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
                                Files.writeString(Path.of(arguments).resolve("loading-hook.txt"), "READY\n");
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
                                if (loading)
                                    Files.writeString(
                                            Path.of(arguments).resolve("loading-hook.txt"), "ERROR " + failure);
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
        LOG.log(System.Logger.Level.INFO, "Profiling observer armed at {0}", arguments);
    }
}
