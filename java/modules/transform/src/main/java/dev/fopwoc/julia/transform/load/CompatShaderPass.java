package dev.fopwoc.julia.transform.load;

import dev.fopwoc.julia.transform.Endpoint;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Preprocesses shader text at the single upstream entry point, before its compatibility AST is built. */
public final class CompatShaderPass {
    private CompatShaderPass() {}

    public static byte[] adapt(String name, byte[] bytes) {
        if (!name.equals("com.gtnewhorizons.angelica.glsm.GLStateManager")) return bytes;
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        int changed = 0;
        for (var method : node.methods) {
            if (!method.name.equals("glShaderSource") || !method.desc.equals("(ILjava/lang/CharSequence;)V")) continue;
            for (var instruction : method.instructions.toArray())
                if (instruction instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals("com/gtnewhorizons/angelica/glsm/CompatShaderTransformer")
                        && call.name.equals("transform")
                        && call.desc.equals("(Ljava/lang/String;IZ)Ljava/lang/String;")) {
                    method.instructions.insertBefore(call, new VarInsnNode(Opcodes.ILOAD, 0));
                    call.owner = Endpoint.COMPAT_SHADER_INPUT.owner();
                    call.desc = "(Ljava/lang/String;IZI)Ljava/lang/String;";
                    changed++;
                }
        }
        if (changed != 1)
            throw new IllegalStateException("Expected one upstream shader compatibility entry, found " + changed);
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
