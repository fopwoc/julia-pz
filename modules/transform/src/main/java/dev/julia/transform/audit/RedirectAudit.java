package dev.julia.transform.audit;

import com.gtnewhorizons.angelica.glsm.redirect.GLSMRedirector;
import dev.julia.audit.model.GraphicsSymbol;
import dev.julia.audit.scan.GraphicsArchiveScanner;
import dev.julia.audit.scan.GraphicsClassScanner;
import dev.julia.transform.Endpoint;
import dev.julia.transform.TargetAbi;
import dev.julia.transform.pass.BridgePipeline;
import dev.julia.utils.io.ClassArchive;
import dev.julia.utils.io.Tsv;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.TreeMap;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

/**
 * Runs the upstream bytecode redirector in memory, optionally followed by Julia's bridge pipeline, and classifies
 * every remaining graphics reference. No transformed class is loaded or saved.
 */
public final class RedirectAudit {
    public static final String USAGE = "--audit-redirects [--compat] new-report.tsv game.jar [mod.jar ...]";

    private final boolean compatibility;
    private final GLSMRedirector redirector = new GLSMRedirector();
    private final TargetAbi abi = new TargetAbi();
    private final GraphicsClassScanner scanner;
    private final Map<String, Integer> counts = new TreeMap<>();
    private int redirected;
    private int capabilityBoundaries;

    private RedirectAudit(boolean compatibility) {
        this.compatibility = compatibility;
        scanner = new GraphicsClassScanner(symbol -> symbol.isGraphicsBinding() || target(symbol.owner()));
    }

    public static void run(String[] args, PrintStream out) throws IOException {
        boolean compatibility = args.length > 0 && args[0].equals("--compat");
        int first = compatibility ? 1 : 0;
        if (args.length < first + 2) throw new IllegalArgumentException("Usage: " + USAGE);
        Path output = Path.of(args[first]).toAbsolutePath();
        if (Files.exists(output)) throw new IllegalArgumentException("Report already exists: " + output);
        Files.createDirectories(output.getParent());
        var audit = new RedirectAudit(compatibility);
        try (var writer = Files.newBufferedWriter(
                output, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            Tsv.row(
                    writer,
                    "archive",
                    "class",
                    "method",
                    "caller_descriptor",
                    "kind",
                    "owner",
                    "name",
                    "descriptor",
                    "status");
            for (int index = first + 1; index < args.length; index++) audit.inspect(Path.of(args[index]), writer);
        }
        out.println("Julia compatibility bridges: " + compatibility);
        out.println("Upstream redirector changed " + audit.redirected + " classes in memory");
        if (compatibility) out.println("Capability boundaries left unadapted: " + audit.capabilityBoundaries);
        audit.counts.forEach((status, count) -> out.println(status + ": " + count));
        out.println("Report: " + output);
        out.println("Signature presence does not establish runtime or shader compatibility.");
    }

    /** Upstream endpoints always; Julia's bridges only when the compatibility pipeline is applied. */
    private boolean target(String owner) {
        return Endpoint.contains(owner)
                && (compatibility
                        || owner.equals(Endpoint.STATE_MANAGER.owner())
                        || owner.equals(Endpoint.DEBUG_SHIM.owner()));
    }

    private void inspect(Path input, Writer writer) throws IOException {
        String archive = input.getFileName().toString();
        ClassArchive.forEachClass(input, (entry, original) -> {
            if (GraphicsArchiveScanner.isBindingClass(entry) || !redirector.shouldTransform(original)) return;
            var node = new ClassNode();
            new ClassReader(original).accept(node, 0);
            if (redirector.transformClassNode(node.name.replace('/', '.'), node, true)) redirected++;
            if (compatibility) node = BridgePipeline.apply(node, blocker -> capabilityBoundaries++);
            var bytecode = new ClassWriter(0);
            node.accept(bytecode);
            for (var reference : scanner.scan(bytecode.toByteArray())) {
                var symbol = reference.target();
                String status = classify(symbol);
                counts.merge(status, 1, Integer::sum);
                Tsv.row(
                        writer,
                        archive,
                        reference.callerClass(),
                        reference.callerMethod(),
                        reference.callerDescriptor(),
                        symbol.kind(),
                        symbol.owner(),
                        symbol.name(),
                        symbol.descriptor(),
                        status);
            }
        });
    }

    private String classify(GraphicsSymbol symbol) throws IOException {
        if (target(symbol.owner())) {
            return abi.contains(symbol.owner(), symbol.name(), symbol.descriptor())
                    ? "TARGET_SIGNATURE_PRESENT"
                    : "TARGET_SIGNATURE_MISSING";
        }
        if (symbol.kind() == GraphicsSymbol.Kind.FIELD) return "HOST_FIELD";
        if (symbol.name().startsWith("gl") || symbol.name().startsWith("ngl")) {
            return symbol.owner().startsWith("org/lwjgl/glfw/") ? "HOST_WINDOW_CALL" : "OPENGL_PASSTHROUGH";
        }
        return "HOST_BINDING_CALL";
    }
}
