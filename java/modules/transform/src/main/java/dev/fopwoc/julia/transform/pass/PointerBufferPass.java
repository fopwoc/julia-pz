package dev.fopwoc.julia.transform.pass;

import com.gtnewhorizons.angelica.glsm.redirect.lwjgl3.CoreOwners;
import dev.fopwoc.julia.transform.Endpoint;
import java.util.LinkedHashMap;
import java.util.Map;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
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

    private record Call(String name, String descriptor, String helper) {
        /** The bridge receives the buffer's address and remaining count where the call passed the buffer. */
        String bridgeDescriptor() {
            return descriptor.replace("L" + POINTER_BUFFER + ";", "JI");
        }
    }

    private static final Call[] CALLS = {
        new Call(
                "glMultiDrawElementsBaseVertex",
                "(ILjava/nio/IntBuffer;IL" + POINTER_BUFFER + ";Ljava/nio/IntBuffer;)V",
                "julia$multiDrawBaseVertex"),
        new Call("glMultiDrawElements", "(ILjava/nio/IntBuffer;IL" + POINTER_BUFFER + ";)V", "julia$multiDrawElements"),
    };

    private PointerBufferPass() {}

    public static void adapt(ClassNode node) {
        Map<Call, Boolean> used = new LinkedHashMap<>();
        boolean onInterface = (node.access & Opcodes.ACC_INTERFACE) != 0;
        for (var method : node.methods)
            for (var instruction : method.instructions)
                if (instruction instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESTATIC
                        && CoreOwners.is(call.owner))
                    for (var known : CALLS)
                        if (call.name.equals(known.name()) && call.desc.equals(known.descriptor())) {
                            call.owner = node.name;
                            call.name = known.helper();
                            call.itf = onInterface;
                            used.put(known, true);
                        }
        for (var call : used.keySet()) {
            if (node.methods.stream().anyMatch(method -> call.helper().equals(method.name)))
                throw new IllegalArgumentException("Reserved Julia helper name: " + call.helper());
            node.methods.add(helper(call));
        }
    }

    private static MethodNode helper(Call call) {
        var helper = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                call.helper(),
                call.descriptor(),
                null,
                null);
        var code = helper.instructions;
        int local = 0, stack = 0;
        for (var argument : Type.getArgumentTypes(call.descriptor())) {
            code.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), local));
            if (argument.getSort() == Type.OBJECT && argument.getInternalName().equals(POINTER_BUFFER)) {
                code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, POINTER_BUFFER, "address", "()J", false));
                code.add(new VarInsnNode(Opcodes.ALOAD, local));
                code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, POINTER_BUFFER, "remaining", "()I", false));
                stack += 3;
            } else stack += argument.getSize();
            local += argument.getSize();
        }
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, Endpoint.DRAW_RANGE_BRIDGE.owner(), call.name(), call.bridgeDescriptor(), false));
        code.add(new InsnNode(Opcodes.RETURN));
        helper.maxLocals = local;
        helper.maxStack = stack;
        return helper;
    }
}
