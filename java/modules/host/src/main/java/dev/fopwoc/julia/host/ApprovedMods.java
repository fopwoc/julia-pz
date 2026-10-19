package dev.fopwoc.julia.host;

import dev.fopwoc.julia.runtime.ModApprovalHooks;
import dev.fopwoc.julia.transform.prepare.ArchivePlan;
import dev.fopwoc.julia.transform.prepare.ArchivePreparation;
import dev.fopwoc.julia.utils.io.Sha256;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Atomically extends the startup plan after approval, before ZombieBuddy exposes a mod's classes or patches. */
public final class ApprovedMods {
    private static final System.Logger LOG = System.getLogger(ApprovedMods.class.getName());
    private final PreparedTransformer transformer;
    private final Supplier<Class<?>[]> loadedClasses;
    private final Path output;
    private final HashMap<Path, String> accepted = new HashMap<>();
    private final LinkedHashMap<Path, String> blocked = new LinkedHashMap<>();
    private ArchivePlan plan;

    public ApprovedMods(
            ArchivePlan plan, PreparedTransformer transformer, Supplier<Class<?>[]> loadedClasses, Path output) {
        this.plan = plan;
        this.transformer = transformer;
        this.loadedClasses = loadedClasses;
        this.output = output;
    }

    public synchronized boolean prepare(ModApprovalHooks.Request request) {
        Path archive = request.archive();
        String hash = "unknown";
        try {
            archive = archive.toRealPath();
            hash = Sha256.of(archive);
            if (request.approvedHash() != null && !hash.equals(request.approvedHash()))
                throw new IllegalStateException("Archive changed after ZombieBuddy approval");
            String previous = accepted.get(archive);
            if (previous != null) {
                if (!previous.equals(hash))
                    throw new IllegalStateException("An active mod archive changed; restart required");
                return true;
            }
            var next = ArchivePreparation.extend(plan, archive);
            if (!next.ready()) {
                var details = new StringBuilder("Unsupported graphics calls; Java mod was not loaded.\n");
                for (var entry : next.entries())
                    for (var blocker : entry.prepared().blockers())
                        details.append(entry.prepared().name())
                                .append(": ")
                                .append(blocker)
                                .append('\n');
                next.blockers().forEach(reason -> details.append(reason).append('\n'));
                return reject(archive, hash, request.packageName(), details.toString());
            }
            var names = next.entries().stream()
                    .skip(plan.entries().size())
                    .map(entry -> entry.prepared().name().replace('/', '.'))
                    .collect(Collectors.toSet());
            for (Class<?> loaded : loadedClasses.get())
                if (names.contains(loaded.getName()))
                    throw new IllegalStateException("Graphics consumer already loaded: " + loaded.getName());
            if (!hash.equals(Sha256.of(archive))) throw new IllegalStateException("Archive changed during preparation");
            transformer.publish(next);
            plan = next;
            accepted.put(archive, hash);
            blocked.remove(archive);
            updateStatus();
            LOG.log(
                    System.Logger.Level.INFO,
                    "Prepared approved Java mod {0}: {1} graphics classes",
                    request.packageName(),
                    names.size());
            return true;
        } catch (Exception failure) {
            LOG.log(System.Logger.Level.ERROR, "Cannot prepare approved mod " + archive, failure);
            return reject(archive, hash, request.packageName(), failure.toString());
        }
    }

    private boolean reject(Path archive, String hash, String name, String details) {
        String reason = name + ": Java mod blocked by Julia; disable it until its graphics calls are supported.";
        blocked.put(archive, reason);
        updateStatus();
        Path report = output.resolve("mod-" + hash + ".txt");
        try {
            Files.writeString(report, "mod=" + name + "\narchive=" + archive + "\nsha256=" + hash + "\n\n" + details);
            LOG.log(System.Logger.Level.ERROR, "{0} Compatibility report: {1}", reason, report);
        } catch (Exception failure) {
            LOG.log(System.Logger.Level.ERROR, "Cannot write mod compatibility report " + report, failure);
        }
        return false;
    }

    private void updateStatus() {
        System.setProperty("julia.mods.blocked", String.join("\n", blocked.values()));
    }
}
