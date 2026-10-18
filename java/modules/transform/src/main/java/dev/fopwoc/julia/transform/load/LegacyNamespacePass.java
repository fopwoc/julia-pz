package dev.fopwoc.julia.transform.load;

import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

/**
 * Applies LWJGL3ify's namespace rule to Angelica and GTNHLib classes: legacy {@code org.lwjgl} references move to
 * {@code org.lwjglx} unless the class is {@code @Lwjgl3Aware}.
 */
public final class LegacyNamespacePass {
    /** Host stand-in for Minecraft's tessellator state, which GTNHLib's DirectTessellator inherits. */
    public static final String TESSELLATOR_STATE = "dev/fopwoc/julia/backend/compat/TessellatorState";

    private static final String AWARE = "Lme/eigenraven/lwjgl3ify/api/Lwjgl3Aware;";
    private static final String TESSELLATOR = "net/minecraft/client/renderer/Tessellator";
    private static final Remapper REMAPPER = new Remapper(Opcodes.ASM9) {
        @Override
        public String map(String type) {
            if (type.equals(TESSELLATOR)) return TESSELLATOR_STATE;
            return type.startsWith("org/lwjgl/") ? "org/lwjglx/" + type.substring("org/lwjgl/".length()) : type;
        }
    };

    private LegacyNamespacePass() {}

    public static byte[] adapt(String className, byte[] bytes) {
        if (!className.startsWith("com.gtnewhorizons.angelica.")
                && !className.startsWith("com.gtnewhorizon.gtnhlib.")) {
            return bytes;
        }
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        if (aware(node)) return bytes;
        var writer = new ClassWriter(0);
        node.accept(new ClassRemapper(writer, REMAPPER));
        return writer.toByteArray();
    }

    private static boolean aware(ClassNode node) {
        return annotated(node.visibleAnnotations) || annotated(node.invisibleAnnotations);
    }

    private static boolean annotated(List<AnnotationNode> annotations) {
        return annotations != null && annotations.stream().anyMatch(annotation -> annotation.desc.equals(AWARE));
    }
}
