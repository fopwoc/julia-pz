package dev.fopwoc.julia.transform.load;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Upstream's unknown state-query defaults must never masquerade as real GPU limits or saved state. */
public final class StrictQueryPass {
    private static final String BACKEND = "com.gtnewhorizons.angelica.sdlgpu.SDLGPURenderBackend";

    private StrictQueryPass() {}

    public static byte[] adapt(String name, byte[] bytes) {
        if (!name.equals(BACKEND)) return bytes;
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        int changed = 0;
        for (var method : node.methods) {
            if (!(method.name.equals("unknownGetInteger") && method.desc.equals("(I)I"))
                    && !(method.name.equals("unknownGetFloat") && method.desc.equals("(I)F"))) continue;
            if ((method.access & Opcodes.ACC_STATIC) == 0)
                throw new IllegalStateException("Unexpected upstream state-query fallback receiver");
            method.instructions.clear();
            method.tryCatchBlocks.clear();
            if (method.localVariables != null) method.localVariables.clear();
            method.visibleLocalVariableAnnotations = null;
            method.invisibleLocalVariableAnnotations = null;
            method.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/UnsupportedOperationException"));
            method.instructions.add(new InsnNode(Opcodes.DUP));
            method.instructions.add(new LdcInsnNode("Unsupported SDL GPU state query " + method.name + ": pname=0x"));
            method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
            method.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, "java/lang/Integer", "toHexString", "(I)Ljava/lang/String;", false));
            method.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "java/lang/String",
                    "concat",
                    "(Ljava/lang/String;)Ljava/lang/String;",
                    false));
            method.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESPECIAL,
                    "java/lang/UnsupportedOperationException",
                    "<init>",
                    "(Ljava/lang/String;)V",
                    false));
            method.instructions.add(new InsnNode(Opcodes.ATHROW));
            method.maxStack = 4;
            changed++;
        }
        if (changed != 2)
            throw new IllegalStateException("Expected two upstream state-query fallbacks, found " + changed);
        var writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
