package dev.julia.transform.pass;

import dev.julia.transform.Endpoint;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Routes only calls with a statically evident null pointer, leaving general uploads as preflight blockers. */
public final class TextureAllocationPass {
    private TextureAllocationPass() {}

    public static void adapt(ClassNode node) {
        for (var method : node.methods) {
            for (var instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)
                        || !Endpoint.STATE_MANAGER.is(call.owner)
                        || !call.name.equals("glTexImage3D") || !call.desc.equals("(IIIIIIIIIJ)V")
                        || call.getOpcode() != Opcodes.INVOKESTATIC) continue;
                var pointer = call.getPrevious();
                while (pointer != null && pointer.getOpcode() == -1) pointer = pointer.getPrevious();
                if (pointer != null && (pointer.getOpcode() == Opcodes.LCONST_0
                        || pointer instanceof LdcInsnNode constant && Long.valueOf(0).equals(constant.cst))) {
                    call.owner = Endpoint.TEXTURE_ALLOCATION_BRIDGE.owner();
                }
            }
        }
    }
}
