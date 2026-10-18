package dev.julia.probe.redirect;

import com.gtnewhorizons.angelica.glsm.redirect.GLSMRedirector;
import dev.julia.audit.model.GraphicsSymbol;
import dev.julia.audit.scan.GraphicsClassScanner;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

/** Runs the upstream bytecode redirector in memory, without loading transformed game classes. */
public final class RedirectAudit {
    private static final String STATE_MANAGER = "com/gtnewhorizons/angelica/glsm/GLStateManager";
    private static final String DEBUG_SHIM = "com/gtnewhorizons/angelica/sdlgpu/compat/AwareGLDebugShim";

    private RedirectAudit() { }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: backend-probe --audit-redirects new-report.tsv game.jar [mod.jar ...]");
        }
        boolean compatibility = args[0].equals("--compat");
        int first = compatibility ? 1 : 0;
        if (args.length < first + 2) throw new IllegalArgumentException("Expected report and input archives");
        Path output = Path.of(args[first]).toAbsolutePath();
        Files.createDirectories(output.getParent());
        var redirector = new GLSMRedirector();
        var scanner = new GraphicsClassScanner(symbol -> symbol.isGraphicsBinding()
                || symbol.owner().equals(STATE_MANAGER) || symbol.owner().equals(DEBUG_SHIM) || symbol.owner().equals(UniformBindings.OWNER) || symbol.owner().equals(TextureAllocationBindings.OWNER));
        var targets = new HashMap<String, Set<String>>();
        targets.put(STATE_MANAGER, signatures(STATE_MANAGER));
        targets.put(DEBUG_SHIM, signatures(DEBUG_SHIM));
        if (compatibility) {
            targets.put(UniformBindings.OWNER, signatures(UniformBindings.OWNER));
            targets.put(TextureAllocationBindings.OWNER, signatures(TextureAllocationBindings.OWNER));
        }
        var counts = new HashMap<String, Integer>();
        int changed = 0;
        try (var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            writer.write("archive\tclass\tmethod\tcaller_descriptor\tkind\towner\tname\tdescriptor\tstatus\n");
            for (int index = first + 1; index < args.length; index++) {
                Path input = Path.of(args[index]);
                try (var jar = new JarFile(input.toFile(), true, JarFile.OPEN_READ, Runtime.version())) {
                    for (var entry : jar.versionedStream().toList()) {
                        String name = entry.getName();
                        if (entry.isDirectory() || !name.endsWith(".class") || name.startsWith("org/lwjgl/")
                                || name.startsWith("org/lwjglx/") || name.endsWith("module-info.class")) continue;
                        byte[] original;
                        try (var stream = jar.getInputStream(entry)) {
                            original = stream.readAllBytes();
                        }
                        if (!redirector.shouldTransform(original)) continue;
                        var node = new ClassNode();
                        new ClassReader(original).accept(node, 0);
                        if (redirector.transformClassNode(node.name.replace('/', '.'), node, true)) changed++;
                        if (compatibility) { UniformBindings.adapt(node); TextureAllocationBindings.adapt(node); }
                        var bytecode = new ClassWriter(0);
                        node.accept(bytecode);
                        for (var reference : scanner.scan(bytecode.toByteArray())) {
                            var symbol = reference.target();
                            String status = classify(symbol, targets);
                            counts.merge(status, 1, Integer::sum);
                            write(writer, input.getFileName().toString(), reference.callerClass(),
                                    reference.callerMethod(), reference.callerDescriptor(), symbol.kind().name(),
                                    symbol.owner(), symbol.name(), symbol.descriptor(), status);
                        }
                    }
                }
            }
        }
        System.out.println("Julia compatibility bridges: " + compatibility);
        System.out.println("Upstream redirector changed " + changed + " classes in memory");
        counts.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> System.out.println(entry.getKey() + ": " + entry.getValue()));
        System.out.println("Report: " + output);
        System.out.println("Signature presence does not establish runtime or shader compatibility.");
    }

    private static String classify(GraphicsSymbol symbol, Map<String, Set<String>> targets) {
        if (targets.containsKey(symbol.owner())) {
            return targets.get(symbol.owner()).contains(symbol.name() + symbol.descriptor())
                    ? "TARGET_SIGNATURE_PRESENT" : "TARGET_SIGNATURE_MISSING";
        }
        if (symbol.kind() == GraphicsSymbol.Kind.FIELD) return "HOST_FIELD";
        if (symbol.name().startsWith("gl") || symbol.name().startsWith("ngl")) {
            return symbol.owner().startsWith("org/lwjgl/glfw/") ? "HOST_WINDOW_CALL" : "OPENGL_PASSTHROUGH";
        }
        return "HOST_BINDING_CALL";
    }

    private static Set<String> signatures(String owner) throws IOException {
        String resource = "/" + owner + ".class";
        try (var input = RedirectAudit.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Missing upstream class resource " + resource);
            var signatures = new HashSet<String>();
            for (var method : ClassFile.of().parse(input.readAllBytes()).methods()) {
                if ((method.flags().flagsMask() & (ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC))
                        == (ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC)) {
                    signatures.add(method.methodName().stringValue() + method.methodType().stringValue());
                }
            }
            return signatures;
        }
    }

    private static void write(BufferedWriter output, String... values) throws IOException {
        for (int index = 0; index < values.length; index++) {
            if (index > 0) output.write('\t');
            output.write(values[index].replace("\\", "\\\\").replace("\t", "\\t")
                    .replace("\r", "\\r").replace("\n", "\\n"));
        }
        output.write('\n');
    }
}
