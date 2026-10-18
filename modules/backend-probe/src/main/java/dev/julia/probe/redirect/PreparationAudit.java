package dev.julia.probe.redirect;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/** Archive-wide dry run. Reports metadata and blockers; never saves transformed game bytecode. */
public final class PreparationAudit {
    private PreparationAudit() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 2) throw new IllegalArgumentException("Usage: --prepare-runtime new-report.tsv game.jar [mod.jar ...]");
        Path output = Path.of(arguments[0]).toAbsolutePath();
        Files.createDirectories(output.getParent());
        int ready = 0;
        int calls = 0;
        int frames = 0;
        ArchivePlan plan;
        try (var writer = Files.newBufferedWriter(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            plan = ArchivePreparation.inspect(Arrays.stream(arguments).skip(1).map(Path::of).toList());
            writer.write("archive\tclass\tsha256\tcalls\tframe_hooks\tstatus\tdetail\n");
            for (var entry : plan.entries()) {
                var result = entry.prepared();
                if (result.ready()) ready++;
                calls += result.calls();
                if (result.frameHooks()) frames++;
                var details = result.ready() ? java.util.List.of("") : result.blockers();
                for (String detail : details) {
                    writer.write(clean(entry.archive()) + "\t" + result.name() + "\t" + entry.sha256() + "\t" + result.calls()
                            + "\t" + result.frameHooks() + "\t" + (result.ready() ? "CLASS_READY" : "BLOCKED") + "\t" + clean(detail) + "\n");
                }
            }
            for (String issue : plan.blockers()) {
                if (issue.startsWith("FRAME_COUNT") || issue.startsWith("DUPLICATE_CLASS")) {
                    writer.write("*\t*\t\t0\tfalse\tBLOCKED\t" + clean(issue) + "\n");
                }
            }
        }
        System.out.println("Prepared classes: " + ready + "/" + plan.entries().size() + "; routed call sites: " + calls + "; frame anchors: " + frames);
        System.out.println("Unique blockers: " + plan.blockers().size() + "; archive activation: " + (plan.ready() ? "CANDIDATE" : "BLOCKED"));
        System.out.println("Report: " + output);
        System.out.println("Class readiness is structural evidence only. No game classes were loaded; native/runtime compatibility is unverified.");
    }

    private static String clean(String text) { return text.replace("\t", " ").replace("\n", " ").replace("\r", " "); }
}
