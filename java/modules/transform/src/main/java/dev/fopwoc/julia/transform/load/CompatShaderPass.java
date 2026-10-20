package dev.fopwoc.julia.transform.load;

import dev.fopwoc.julia.transform.Endpoint;
import dev.fopwoc.julia.transform.asm.Anchors;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Preprocesses shader text at the single upstream entry point, before its compatibility AST is built. */
public final class CompatShaderPass implements UpstreamPatch {

    @Override
    public boolean targets(String name) {
        return name.equals("com.gtnewhorizons.angelica.glsm.GLStateManager");
    }

    @Override
    public boolean apply(String name, ClassNode node) {
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
        Anchors.require("shader compatibility entry", changed, 1);
        return true;
    }
}
