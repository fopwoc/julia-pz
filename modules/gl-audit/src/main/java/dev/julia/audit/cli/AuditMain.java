package dev.julia.audit.cli;

import dev.julia.audit.model.ArchiveAudit;
import dev.julia.audit.model.GraphicsReference;
import dev.julia.audit.report.TsvAuditWriter;
import dev.julia.audit.scan.GraphicsArchiveScanner;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

public final class AuditMain {
    private AuditMain() { }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || (args.length == 1 && args[0].equals("--help"))) {
            out.println("Usage: gl-audit [--output report.tsv] game.jar [mod.jar ...]");
            return args.length == 0 ? 2 : 0;
        }
        try {
            Path output = null;
            var inputs = new ArrayList<Path>();
            for (int index = 0; index < args.length; index++) {
                if (args[index].equals("--output")) {
                    if (output != null || ++index == args.length) {
                        throw new IllegalArgumentException("Supply --output once, followed by a file path");
                    }
                    output = Path.of(args[index]).toAbsolutePath().normalize();
                } else if (args[index].startsWith("--")) {
                    throw new IllegalArgumentException("Unknown option: " + args[index]);
                } else {
                    inputs.add(Path.of(args[index]).toRealPath());
                }
            }
            if (inputs.isEmpty()) throw new IllegalArgumentException("Supply at least one JAR");
            if (output != null && Files.exists(output)) {
                for (var input : inputs) {
                    if (Files.isSameFile(output, input)) throw new IllegalArgumentException("Output would overwrite an input JAR");
                }
            }
            var scanner = new GraphicsArchiveScanner();
            var audits = new ArrayList<ArchiveAudit>();
            for (var input : inputs) {
                var audit = scanner.scan(input);
                audits.add(audit);
                long unique = audit.references().stream().map(GraphicsReference::target).distinct().count();
                out.printf("%s: %,d classes, %,d binding classes excluded, %,d references, %,d unique symbols%n",
                        audit.archive(), audit.classesScanned(), audit.bindingsExcluded(), audit.references().size(), unique);
            }
            if (output != null) {
                Files.createDirectories(output.getParent());
                try (var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
                    new TsvAuditWriter().write(audits, writer);
                }
                out.println("Report: " + output);
            }
            return 0;
        } catch (Exception exception) {
            err.println("Graphics audit failed:");
            exception.printStackTrace(err);
            return 1;
        }
    }
}
