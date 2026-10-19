package dev.fopwoc.julia.transform.prepare;

import com.gtnewhorizons.angelica.glsm.redirect.GLSMRedirector;
import dev.fopwoc.julia.audit.model.GraphicsSymbol;
import dev.fopwoc.julia.audit.scan.GraphicsClassScanner;
import dev.fopwoc.julia.transform.Endpoint;
import dev.fopwoc.julia.transform.TargetAbi;
import dev.fopwoc.julia.transform.pass.BridgePipeline;
import dev.fopwoc.julia.transform.pass.DispatchPass;
import dev.fopwoc.julia.transform.pass.FramePass;
import dev.fopwoc.julia.transform.pass.ImGuiPass;
import dev.fopwoc.julia.transform.pass.UnsupportedCallPass;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

/** Complete per-class analysis before exposing bytes for runtime definition. No game classes are loaded. */
public final class RuntimePreparation {
    private static final String LEGACY_GL = "org/lwjglx/opengl/";

    private final GLSMRedirector upstream = new GLSMRedirector();
    private final TargetAbi abi = new TargetAbi();
    private final GraphicsClassScanner scanner = new GraphicsClassScanner(RuntimePreparation::isGraphicsConsumer);

    /** A reference that makes its class subject to preparation: a graphics binding or a runtime endpoint. */
    public static boolean isGraphicsConsumer(GraphicsSymbol symbol) {
        return symbol.isGraphicsBinding() || Endpoint.contains(symbol.owner());
    }

    public synchronized PreparedClass prepare(byte[] original) throws IOException {
        return prepare(original, false);
    }

    public synchronized PreparedClass prepareMod(byte[] original) throws IOException {
        return prepare(original, true);
    }

    private PreparedClass prepare(byte[] original, boolean deferred) throws IOException {
        var node = new ClassNode();
        new ClassReader(original).accept(node, 0);
        if (upstream.shouldTransform(original)) upstream.transformClassNode(node.name.replace('/', '.'), node, true);
        var blockers = new TreeSet<String>();
        node = BridgePipeline.apply(node, blockers::add);

        List<String> unsupported = List.of();
        if (deferred) {
            unsupported = UnsupportedCallPass.adapt(node, this::classify);
            // Native capability storage may remain; its actual binding accesses now throw.
            blockers.removeIf(reason -> reason.startsWith("CAPABILITY_BOUNDARY "));
        }
        var snapshot = new ClassWriter(0);
        node.accept(snapshot);
        for (var reference : scanner.scan(snapshot.toByteArray())) {
            String blocker = classify(reference.target());
            if (blocker != null) blockers.add(blocker);
        }

        boolean frames = false;
        if (node.name.equals(FramePass.RENDER_THREAD)) {
            try {
                frames = FramePass.adapt(node);
            } catch (IllegalArgumentException failure) {
                blockers.add("FRAME_ANCHOR " + failure.getMessage());
            }
        }
        int calls = blockers.isEmpty() ? DispatchPass.adapt(node) : 0;
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return new PreparedClass(
                node.name,
                writer.toByteArray(),
                new ArrayList<>(blockers),
                calls,
                frames,
                ImGuiPass.requiredConsumers(node),
                unsupported);
    }

    /** @return the blocker a remaining reference represents, or {@code null} when it is safe to route or keep */
    private String classify(GraphicsSymbol symbol) throws IOException {
        String key = symbol.owner() + "." + symbol.name() + symbol.descriptor();
        if (Endpoint.contains(symbol.owner())) {
            if (symbol.kind() != GraphicsSymbol.Kind.METHOD
                    || !abi.contains(symbol.owner(), symbol.name(), symbol.descriptor())) {
                return "MISSING_TARGET " + key;
            }
            if (Endpoint.STATE_MANAGER.is(symbol.owner()) && symbol.name().equals("glGetUniform")) {
                return "UNIMPLEMENTED_BACKEND " + key;
            }
            try {
                DispatchPass.checkDescriptor(symbol.descriptor());
            } catch (IllegalArgumentException failure) {
                return "ISOLATION_TYPE " + key;
            }
            return null;
        }
        if (symbol.owner().startsWith("org/lwjgl/opengl/") || symbol.owner().startsWith("org/lwjgl/util/glu/")) {
            return "NATIVE_OPENGL " + key;
        }
        if (symbol.owner().equals(LEGACY_GL + "Display") && symbol.name().equals("drawImGuiDrawData")) {
            return "HOST_RENDERER " + key;
        }
        if (symbol.owner().startsWith(LEGACY_GL)
                && (symbol.owner().substring(LEGACY_GL.length()).startsWith("GL")
                        || symbol.name().startsWith("gl")
                        || symbol.name().startsWith("ngl"))) {
            return "LEGACY_OPENGL " + key;
        }
        return null;
    }
}
