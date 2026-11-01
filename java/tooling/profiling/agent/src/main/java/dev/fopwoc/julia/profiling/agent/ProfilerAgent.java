package dev.fopwoc.julia.profiling.agent;

import dev.fopwoc.julia.utils.logging.LogConfiguration;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
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
        for (Class<?> loaded : instrumentation.getAllLoadedClasses())
            if (loaded.getName().equals(FrameInstrumentation.TARGET.replace('/', '.')))
                throw new IllegalStateException("Frame owner loaded before profiler installation");
        var controller = new CaptureController(Path.of(arguments));
        // Retransformation-capable observers follow the incapable transformers used by Julia's prepared classes.
        instrumentation.addTransformer(
                new ClassFileTransformer() {
                    @Override
                    public byte[] transform(
                            ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
                        if (!FrameInstrumentation.TARGET.equals(name)) return null;
                        try {
                            var instrumented = FrameInstrumentation.instrument(bytes, loader);
                            controller.frameOwnerReady();
                            return instrumented;
                        } catch (RuntimeException | IOException failure) {
                            LOG.log(
                                    System.Logger.Level.ERROR,
                                    "Frame observer could not be installed; measurement is invalid",
                                    failure);
                            try {
                                controller.status("ERROR " + failure);
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
