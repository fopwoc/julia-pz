package dev.fopwoc.julia.host;

import dev.fopwoc.julia.runtime.FatalGraphics;
import dev.fopwoc.julia.runtime.ModApprovalHooks;
import dev.fopwoc.julia.runtime.StartupHooks;
import dev.fopwoc.julia.transform.prepare.ArchivePreparation;
import dev.fopwoc.julia.utils.logging.LogConfiguration;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;

/** Preflights the complete supplied archive set before installing any live class transformation. */
public final class GameStartup {
    private static final System.Logger LOG = System.getLogger(GameStartup.class.getName());

    private GameStartup() {}

    public static void install(Instrumentation instrumentation, Path output, Path[] inputs, ClassLoader host)
            throws Exception {
        LogConfiguration.configure();
        FatalGraphics.arm(output);
        try {
            var plan = ArchivePreparation.inspect(List.of(inputs));
            Files.writeString(
                    output.resolve("preparation.txt"),
                    "classes=" + plan.entries().size() + "\nblockers=" + plan.blockers() + "\n",
                    StandardOpenOption.CREATE_NEW);
            if (!plan.ready()) throw new IllegalStateException("Startup preparation blocked: " + plan.blockers());
            var candidates = new HashSet<String>();
            for (var entry : plan.entries())
                candidates.add(entry.prepared().name().replace('/', '.'));
            for (Class<?> loaded : instrumentation.getAllLoadedClasses())
                if (candidates.contains(loaded.getName()))
                    throw new IllegalStateException("Graphics consumer already loaded: " + loaded.getName());
            var transformer = new PreparedTransformer(plan, host);
            var prepared = LiveRuntime.prepareDeveloperHud();
            StartupHooks.install(() -> {
                try {
                    LOG.log(
                            System.Logger.Level.INFO,
                            "Opening live graphics runtime on {0}",
                            Thread.currentThread().getName());
                    return LiveRuntime.open(host, output, prepared);
                } catch (Exception | Error failure) {
                    LOG.log(System.Logger.Level.ERROR, "Live graphics startup failed", failure);
                    var text = new StringWriter();
                    failure.printStackTrace(new PrintWriter(text));
                    try {
                        Files.writeString(output.resolve("startup-error.txt"), text.toString());
                    } catch (Exception report) {
                        failure.addSuppressed(report);
                    }
                    throw FatalGraphics.failure("Live graphics startup failed", failure);
                }
            });
            var mods = new ApprovedMods(plan, transformer, instrumentation::getAllLoadedClasses, output);
            ModApprovalHooks.install(mods::prepare);
            instrumentation.addTransformer(transformer);
            System.setProperty("julia.startup", "active");
            LOG.log(
                    System.Logger.Level.INFO,
                    "Julia startup armed: {0} prepared classes; waiting for the render context",
                    plan.entries().size());
        } catch (Throwable failure) {
            throw FatalGraphics.failure("Cannot prepare live graphics startup", failure);
        }
    }
}
