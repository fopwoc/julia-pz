package dev.fopwoc.julia.host;

import dev.fopwoc.julia.runtime.FatalGraphics;
import dev.fopwoc.julia.runtime.ModApprovalHooks;
import dev.fopwoc.julia.runtime.StartupHooks;
import dev.fopwoc.julia.transform.prepare.ArchivePlan;
import dev.fopwoc.julia.transform.prepare.ArchivePreparation;
import dev.fopwoc.julia.utils.logging.LogConfiguration;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** Preflights the complete supplied archive set before installing any live class transformation. */
public final class GameStartup {
    private static final System.Logger LOG = System.getLogger(GameStartup.class.getName());

    private static final String BUDDY_LOADER = "me.zed_0xff.zombie_buddy.Loader";
    private static final String BUDDY_PATCHES = "me.zed_0xff.zombie_buddy.patches.";

    private GameStartup() {}

    /**
     * Consumers that loaded before Julia and cannot simply be tolerated.
     *
     * <p>The exposer only gains Julia's Lua status, which a preloaded Julia registers through ZombieBuddy instead. Patch
     * classes hold advice that ZombieBuddy weaves into its targets, so what they reference never runs from them.
     */
    static List<String> loadedConsumers(Set<String> candidates, Stream<String> loaded) {
        String exposer = ArchivePreparation.LUA_EXPOSER.replace('/', '.');
        return loaded.filter(name -> !name.equals(exposer) && !name.startsWith(BUDDY_PATCHES))
                .filter(candidates::contains)
                .sorted()
                .toList();
    }

    /**
     * ZombieBuddy's loader is already running when a preload mod starts, but only its rewritten form lets Julia prepare
     * the mods it approves later. Frames on the stack finish with the old code, which is fine: none of them reach approval.
     */
    private static void adoptBuddyLoader(Instrumentation instrumentation, ArchivePlan plan) throws Exception {
        var prepared = plan.entries().stream()
                .filter(entry -> entry.prepared().name().equals(BUDDY_LOADER.replace('.', '/')))
                .findFirst()
                .orElseThrow()
                .prepared()
                .requireBytecode();
        for (Class<?> loaded : instrumentation.getAllLoadedClasses())
            if (loaded.getName().equals(BUDDY_LOADER))
                instrumentation.redefineClasses(new ClassDefinition(loaded, prepared));
    }

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
            var early = loadedConsumers(
                    candidates,
                    Arrays.stream(instrumentation.getAllLoadedClasses()).map(loaded -> loaded.getName()));
            for (String name : early) {
                if (name.equals(BUDDY_LOADER)) adoptBuddyLoader(instrumentation, plan);
                else throw new IllegalStateException("Graphics consumers already loaded: " + early);
            }
            var transformer = new PreparedTransformer(plan, host);
            StartupHooks.install(() -> {
                try {
                    LOG.log(
                            System.Logger.Level.INFO,
                            "Opening live graphics runtime on {0}",
                            Thread.currentThread().getName());
                    return LiveRuntime.open(host, output);
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
