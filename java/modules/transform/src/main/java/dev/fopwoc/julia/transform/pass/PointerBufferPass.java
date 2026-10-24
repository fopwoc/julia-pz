package dev.fopwoc.julia.transform.pass;

import dev.fopwoc.julia.transform.Endpoint;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * LWJGL's {@code PointerBuffer} cannot cross into the isolated runtime. Calls taking one go through a private helper
 * in the caller that passes the buffer's current address and remaining count instead.
 */
public final class PointerBufferPass {
    private static final String POINTER_BUFFER = "org/lwjgl/PointerBuffer";
    private static final String MULTI_DRAW = "glMultiDrawElementsBaseVertex";
    private static final String MULTI_DRAW_DESCRIPTOR =
            "(ILjava/nio/IntBuffer;IL" + POINTER_BUFFER + ";Ljava/nio/IntBuffer;)V";
    private static final String BRIDGE_DESCRIPTOR = "(ILjava/nio/IntBuffer;IJILjava/nio/IntBuffer;)V";
    private static final String HELPER = "julia$multiDrawBaseVertex";

    private PointerBufferPass() {}

    public static void adapt(ClassNode node) {
        boolean used = false;
        boolean onInterface = (node.access & Opcodes.ACC_INTERFACE) != 0;
        for (var method : node.methods)
            for (var instruction : method.instructions)
                if (instruction instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESTATIC
                        && CoreOwners.is(call.owner)
                        && call.name.equals(MULTI_DRAW)
                        && call.desc.equals(MULTI_DRAW_DESCRIPTOR)) {
                    call.owner = node.name;
                    call.name = HELPER;
                    call.itf = onInterface;
                    used = true;
                }
        if (!used) return;
        if (node.methods.stream().anyMatch(method -> HELPER.equals(method.name)))
            throw new IllegalArgumentException("Reserved Julia helper name: " + HELPER);
        node.methods.add(helper());
    }

    private static MethodNode helper() {
        var helper = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                HELPER,
                MULTI_DRAW_DESCRIPTOR,
                null,
                null);
        var code = helper.instructions;
        code.add(new VarInsnNode(Opcodes.ILOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, POINTER_BUFFER, "address", "()J", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, POINTER_BUFFER, "remaining", "()I", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, Endpoint.DRAW_RANGE_BRIDGE.owner(), MULTI_DRAW, BRIDGE_DESCRIPTOR, false));
        code.add(new InsnNode(Opcodes.RETURN));
        helper.maxLocals = 5;
        helper.maxStack = 7;
        return helper;
    }
}
