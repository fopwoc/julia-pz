package dev.julia.probe.redirect;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Routes only calls with a statically evident null pointer, leaving general uploads as preflight blockers. */
public final class TextureAllocationBindings {
    public static final String OWNER = "dev/julia/probe/adapter/TextureAllocationBridge";
    private TextureAllocationBindings() { }

    public static void adapt(ClassNode node) {
        for (var method : node.methods) {
            for (var instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)
                        || !call.owner.equals("com/gtnewhorizons/angelica/glsm/GLStateManager")
                        || !call.name.equals("glTexImage3D") || !call.desc.equals("(IIIIIIIIIJ)V")
                        || call.getOpcode() != Opcodes.INVOKESTATIC) continue;
                var pointer = call.getPrevious();
                while (pointer != null && pointer.getOpcode() == -1) pointer = pointer.getPrevious();
                if (pointer != null && (pointer.getOpcode() == Opcodes.LCONST_0
                        || pointer instanceof LdcInsnNode constant && Long.valueOf(0).equals(constant.cst))) {
                    call.owner = OWNER;
                }
            }
        }
    }
}
