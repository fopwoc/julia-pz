package dev.fopwoc.julia.transform.load;

import com.gtnewhorizons.angelica.glsm.redirect.GLSMRedirector;
import dev.fopwoc.julia.audit.scan.GraphicsClassScanner;
import dev.fopwoc.julia.transform.pass.BridgePipeline;
import dev.fopwoc.julia.transform.pass.DispatchPass;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

/**
 * Load-time transform for a controlled scene: upstream redirection, Julia's bridges, then typed dispatch.
 *
 * <p>Rejects the class if any native OpenGL call would remain, before it can ever execute.
 */
public final class SceneTransform {
    private static final GraphicsClassScanner NATIVE_GL = new GraphicsClassScanner(symbol ->
            symbol.owner().startsWith("org/lwjgl/opengl/") || symbol.owner().startsWith("org/lwjglx/opengl/"));

    private SceneTransform() {}

    public static byte[] adapt(byte[] original) {
        var node = new ClassNode();
        new ClassReader(original).accept(node, 0);
        String name = node.name;
        if (!new GLSMRedirector().transformClassNode(name.replace('/', '.'), node, true)) {
            throw new IllegalStateException("Controlled scene was not redirected: " + name);
        }
        node = BridgePipeline.apply(node, blocker -> {
            throw new IllegalStateException("Scene " + name + " blocked: " + blocker);
        });
        var snapshot = new ClassWriter(0);
        node.accept(snapshot);
        var remaining = NATIVE_GL.scan(snapshot.toByteArray());
        if (!remaining.isEmpty()) throw new IllegalStateException("Unredirected scene calls: " + remaining);
        DispatchPass.adapt(node);
        var routed = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(routed);
        return routed.toByteArray();
    }
}
