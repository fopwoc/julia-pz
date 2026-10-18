package dev.julia.transform.audit;

import dev.julia.transform.prepare.ArchivePlan;
import dev.julia.transform.prepare.ArchivePreparation;
import dev.julia.transform.prepare.PreparedClass;
import dev.julia.utils.io.Tsv;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;

/** Archive-wide dry run. Reports metadata and blockers; never saves transformed game bytecode. */
public final class PreparationAudit {
    public static final String USAGE = "--prepare-runtime new-report.tsv game.jar [mod.jar ...]";

    private PreparationAudit() {}

    public static void run(String[] arguments, PrintStream out) throws IOException {
        if (arguments.length < 2) throw new IllegalArgumentException("Usage: " + USAGE);
        Path output = Path.of(arguments[0]).toAbsolutePath();
        if (Files.exists(output)) throw new IllegalArgumentException("Report already exists: " + output);
        var plan = ArchivePreparation.inspect(
                Arrays.stream(arguments).skip(1).map(Path::of).toList());
        Files.createDirectories(output.getParent());
        try (var writer = Files.newBufferedWriter(
                output, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            Tsv.row(writer, "archive", "class", "sha256", "calls", "frame_hooks", "status", "detail");
            for (var entry : plan.entries()) {
                var result = entry.prepared();
                String status = result.ready() ? "CLASS_READY" : "BLOCKED";
                for (String detail : result.ready() ? List.of("") : result.blockers()) {
                    Tsv.row(
                            writer,
                            entry.archive(),
                            result.name(),
                            entry.sha256(),
                            result.calls(),
                            result.frameHooks(),
                            status,
                            detail);
                }
            }
            for (String issue : plan.blockers()) {
                if (issue.startsWith("FRAME_COUNT") || issue.startsWith("DUPLICATE_CLASS")) {
                    Tsv.row(writer, "*", "*", "", 0, false, "BLOCKED", issue);
                }
            }
        }
        summarize(plan, output, out);
    }

    private static void summarize(ArchivePlan plan, Path output, PrintStream out) {
        var classes = plan.entries().stream().map(ArchivePlan.Entry::prepared).toList();
        long ready = classes.stream().filter(PreparedClass::ready).count();
        int calls = classes.stream().mapToInt(PreparedClass::calls).sum();
        long frames = classes.stream().filter(PreparedClass::frameHooks).count();
        out.printf(
                "Prepared classes: %d/%d; routed call sites: %d; frame anchors: %d%n",
                ready, classes.size(), calls, frames);
        out.printf(
                "Unique blockers: %d; archive activation: %s%n",
                plan.blockers().size(), plan.ready() ? "CANDIDATE" : "BLOCKED");
        out.println("Report: " + output);
        out.println("Class readiness is structural evidence only. No game classes were loaded;"
                + " native/runtime compatibility is unverified.");
    }
}
