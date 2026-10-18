package dev.julia.probe.redirect;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import static org.junit.jupiter.api.Assertions.*;

class TextureAllocationBindingsTest {
    @Test void routesOnlyProvenNullStorageCalls() {
        var node = new ClassNode();
        var method = new MethodNode();
        node.methods.add(method);
        var zero = call();
        method.instructions.add(new InsnNode(Opcodes.LCONST_0));
        method.instructions.add(new LabelNode());
        method.instructions.add(zero);
        var constant = call();
        method.instructions.add(new LdcInsnNode(0L));
        method.instructions.add(constant);
        var address = call();
        method.instructions.add(new LdcInsnNode(4096L));
        method.instructions.add(address);
        var variable = call();
        method.instructions.add(new VarInsnNode(Opcodes.LLOAD, 0));
        method.instructions.add(variable);
        TextureAllocationBindings.adapt(node);
        assertEquals(TextureAllocationBindings.OWNER, zero.owner);
        assertEquals(TextureAllocationBindings.OWNER, constant.owner);
        assertEquals("com/gtnewhorizons/angelica/glsm/GLStateManager", address.owner);
        assertEquals("com/gtnewhorizons/angelica/glsm/GLStateManager", variable.owner);
    }

    private static MethodInsnNode call() {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, "com/gtnewhorizons/angelica/glsm/GLStateManager",
                "glTexImage3D", "(IIIIIIIIIJ)V", false);
    }
}
