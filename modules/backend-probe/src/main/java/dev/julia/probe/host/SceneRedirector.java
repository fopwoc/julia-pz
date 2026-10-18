package dev.julia.probe.host;

import com.gtnewhorizons.angelica.glsm.redirect.GLSMRedirector;
import dev.julia.audit.scan.GraphicsClassScanner;
import dev.julia.probe.redirect.UniformBindings;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

/** Routes the controlled scene through upstream GLSM and rejects every remaining native GL call. */
final class SceneRedirector {
    private SceneRedirector() { }

    static byte[] adapt(byte[] original) {
        var node = new ClassNode();
        new ClassReader(original).accept(node, 0);
        if (!new GLSMRedirector().transformClassNode(node.name.replace('/', '.'), node, true)) {
            throw new IllegalStateException("Controlled scene was not redirected");
        }
        UniformBindings.adapt(node);
        var writer = new ClassWriter(0);
        node.accept(writer);
        byte[] transformed = writer.toByteArray();
        var scanner = new GraphicsClassScanner(symbol -> symbol.owner().startsWith("org/lwjgl/opengl/")
                || symbol.owner().startsWith("org/lwjglx/opengl/"));
        var remaining = scanner.scan(transformed);
        if (!remaining.isEmpty()) throw new IllegalStateException("Unredirected scene calls: " + remaining);
        return transformed;
    }
}
