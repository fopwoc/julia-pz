package dev.julia.probe.host;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;

/** Applies LWJGL3ify's legacy namespace rule only to legacy graphics-library consumers. */
final class LegacyBindings {
    private static final String AWARE = "Lme/eigenraven/lwjgl3ify/api/Lwjgl3Aware;";

    private LegacyBindings() { }

    static byte[] adapt(String name, byte[] bytes) {
        if (!name.startsWith("com.gtnewhorizons.angelica.") && !name.startsWith("com.gtnewhorizon.gtnhlib.")) {
            return bytes;
        }
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        if (node.visibleAnnotations != null && node.visibleAnnotations.stream().anyMatch(a -> a.desc.equals(AWARE))
                || node.invisibleAnnotations != null && node.invisibleAnnotations.stream().anyMatch(a -> a.desc.equals(AWARE))) {
            return bytes;
        }
        var writer = new ClassWriter(0);
        var remapper = new Remapper(Opcodes.ASM9) {
            @Override public String map(String type) {
                if (type.equals("net/minecraft/client/renderer/Tessellator")) {
                    return "dev/julia/probe/adapter/TessellatorState";
                }
                return type.startsWith("org/lwjgl/") ? "org/lwjglx/" + type.substring("org/lwjgl/".length()) : type;
            }
        };
        node.accept(new ClassRemapper(writer, remapper));
        return writer.toByteArray();
    }
}
