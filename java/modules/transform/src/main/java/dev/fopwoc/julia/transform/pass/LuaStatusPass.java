package dev.fopwoc.julia.transform.pass;

import dev.fopwoc.julia.transform.Endpoint;
import dev.fopwoc.julia.transform.prepare.ArchivePreparation;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Adds a read-only agent status class to PZ's normal Lua exposure set, without ZombieBuddy. */
public final class LuaStatusPass {
    private LuaStatusPass() {}

    public static void adapt(ClassNode node) {
        if (!node.name.equals(ArchivePreparation.LUA_EXPOSER)) return;
        var methods = node.methods.stream()
                .filter(m -> m.name.equals("exposeAll") && m.desc.equals("()V") && (m.access & Opcodes.ACC_STATIC) == 0)
                .toList();
        if (methods.size() != 1) throw new IllegalArgumentException("Expected one Lua exposeAll boundary");
        var method = methods.getFirst();
        for (var instruction : method.instructions)
            if (instruction instanceof LdcInsnNode constant
                    && constant.cst.equals(Type.getObjectType(Endpoint.LUA_STATUS.owner())))
                throw new IllegalArgumentException("Lua status already exposed");
        var exposure = new InsnList();
        exposure.add(new VarInsnNode(Opcodes.ALOAD, 0));
        exposure.add(new LdcInsnNode(Type.getObjectType(Endpoint.LUA_STATUS.owner())));
        exposure.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "setExposed", "(Ljava/lang/Class;)V", false));
        method.instructions.insert(exposure);
        method.maxStack = Math.max(2, method.maxStack);
    }
}
