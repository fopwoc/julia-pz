package dev.fopwoc.julia.transform.load;

import dev.fopwoc.julia.transform.Endpoint;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Replaces the backend's Minecraft resource-loader dependency with its own published shader assets. */
public final class ShaderSourcePass {
    private ShaderSourcePass() {}

    public static byte[] adapt(String name, byte[] bytes) {
        if (!name.startsWith("com.gtnewhorizons.angelica.sdlgpu.")) return bytes;
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        boolean changed = false;
        for (var method : node.methods)
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("org/embeddedt/embeddium/impl/render/shader/ShaderLoader")
                        && call.name.equals("getShaderSource")
                        && call.desc.equals("(Ljava/lang/String;)Ljava/lang/String;")) {
                    call.owner = Endpoint.SHADER_SOURCES.owner();
                    changed = true;
                }
            }
        if (!changed) return bytes;
        var writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
