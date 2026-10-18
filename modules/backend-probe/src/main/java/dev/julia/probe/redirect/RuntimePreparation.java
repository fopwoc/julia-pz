package dev.julia.probe.redirect;

import com.gtnewhorizons.angelica.glsm.redirect.GLSMRedirector;
import dev.julia.audit.model.GraphicsSymbol;
import dev.julia.audit.scan.GraphicsClassScanner;
import java.io.IOException;
import java.util.ArrayList;
import java.util.TreeSet;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

/** Complete per-class analysis before exposing bytes for runtime definition. No game classes are loaded. */
public final class RuntimePreparation {
    private final GLSMRedirector upstream = new GLSMRedirector();
    private final TargetAbi abi = new TargetAbi();
    private final GraphicsClassScanner scanner = new GraphicsClassScanner(symbol -> symbol.isGraphicsBinding() || RuntimeCalls.target(symbol.owner()));

    public synchronized PreparedClass prepare(byte[] original) throws IOException {
        var node = new ClassNode();
        new ClassReader(original).accept(node, 0);
        if (upstream.shouldTransform(original)) upstream.transformClassNode(node.name.replace('/', '.'), node, true);
        var blockers = new TreeSet<String>();
        try { node = CapabilityBindings.adapt(node); }
        catch (IllegalArgumentException failure) { blockers.add("CAPABILITY_BOUNDARY " + failure.getMessage()); }
        VanillaAliases.adapt(node);
        UniformBindings.adapt(node);
        TextureAllocationBindings.adapt(node);
        var snapshot = new ClassWriter(0);
        node.accept(snapshot);
        for (var reference : scanner.scan(snapshot.toByteArray())) {
            var symbol = reference.target();
            String key = symbol.owner() + "." + symbol.name() + symbol.descriptor();
            if (RuntimeCalls.target(symbol.owner())) {
                if (symbol.kind() != GraphicsSymbol.Kind.METHOD || !abi.contains(symbol.owner(), symbol.name(), symbol.descriptor())) {
                    blockers.add("MISSING_TARGET " + key);
                } else if (symbol.owner().equals("com/gtnewhorizons/angelica/glsm/GLStateManager")
                        && symbol.name().equals("glGetUniform")) {
                    blockers.add("UNIMPLEMENTED_BACKEND " + key);
                } else {
                    try { RuntimeCalls.checkDescriptor(symbol.descriptor()); }
                    catch (IllegalArgumentException failure) { blockers.add("ISOLATION_TYPE " + key); }
                }
            } else if (symbol.owner().startsWith("org/lwjgl/opengl/")
                    || symbol.owner().startsWith("org/lwjgl/util/glu/")) {
                blockers.add("NATIVE_OPENGL " + key);
            } else if (symbol.owner().equals("org/lwjglx/opengl/Display") && symbol.name().equals("drawImGuiDrawData")) {
                blockers.add("HOST_RENDERER " + key);
            } else if (symbol.owner().startsWith("org/lwjglx/opengl/")
                    && (symbol.owner().substring("org/lwjglx/opengl/".length()).startsWith("GL")
                    || symbol.name().startsWith("gl") || symbol.name().startsWith("ngl"))) {
                blockers.add("LEGACY_OPENGL " + key);
            }
        }
        boolean frames = false;
        if (node.name.equals(FrameBindings.RENDER_THREAD)) {
            try { frames = FrameBindings.adapt(node); }
            catch (IllegalArgumentException failure) { blockers.add("FRAME_ANCHOR " + failure.getMessage()); }
        }
        int calls = 0;
        if (blockers.isEmpty()) calls = RuntimeCalls.adapt(node);
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return new PreparedClass(node.name, writer.toByteArray(), new ArrayList<>(blockers), calls, frames);
    }
}
