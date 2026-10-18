package dev.julia.transform.pass;

import dev.julia.transform.Endpoint;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Replaces endpoint calls and method handles with typed {@code invokedynamic} call sites, so transformed consumers
 * never link against the isolated runtime's classes.
 */
public final class DispatchPass {
    private static final Handle BOOTSTRAP = new Handle(
            Opcodes.H_INVOKESTATIC,
            "dev/julia/runtime/RenderDispatch",
            "bootstrap",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;)"
                    + "Ljava/lang/invoke/CallSite;",
            false);
    private static final String HELPER_PREFIX = "julia$dispatch$";

    private DispatchPass() {}

    /** @return the number of rewritten call sites, counting each synthesized handle helper */
    public static int adapt(ClassNode node) {
        var helpers = new ArrayList<MethodNode>();
        int count = 0;
        for (var method : node.methods) {
            for (var instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode call && Endpoint.contains(call.owner)) {
                    if (call.getOpcode() != Opcodes.INVOKESTATIC)
                        throw new IllegalArgumentException("Nonstatic backend call");
                    checkDescriptor(call.desc);
                    method.instructions.set(call, dynamic(call.owner, call.name, call.desc));
                    count++;
                } else if (instruction instanceof LdcInsnNode constant) {
                    constant.cst = constant(node, helpers, constant.cst);
                } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                    dynamic.bsm = (Handle) constant(node, helpers, dynamic.bsm);
                    for (int i = 0; i < dynamic.bsmArgs.length; i++) {
                        dynamic.bsmArgs[i] = constant(node, helpers, dynamic.bsmArgs[i]);
                    }
                }
            }
        }
        node.methods.addAll(helpers);
        return count + helpers.size();
    }

    /** Only JDK types may cross the boundary; anything else would link the caller to isolated classes. */
    public static void checkDescriptor(String descriptor) {
        checkType(Type.getReturnType(descriptor));
        for (var argument : Type.getArgumentTypes(descriptor)) checkType(argument);
    }

    private static void checkType(Type type) {
        if (type.getSort() == Type.ARRAY) {
            checkType(type.getElementType());
        } else if (type.getSort() == Type.OBJECT && !type.getInternalName().startsWith("java/")) {
            throw new IllegalArgumentException(
                    "Non-JDK type crosses graphics runtime boundary: " + type.getDescriptor());
        }
    }

    private static InvokeDynamicInsnNode dynamic(String owner, String name, String descriptor) {
        return new InvokeDynamicInsnNode(name, descriptor, BOOTSTRAP, owner);
    }

    private static Object constant(ClassNode node, List<MethodNode> helpers, Object value) {
        if (value instanceof Handle handle && Endpoint.contains(handle.getOwner())) {
            return helperHandle(node, helpers, handle);
        }
        if (value instanceof ConstantDynamic dynamic) {
            Object[] arguments = new Object[dynamic.getBootstrapMethodArgumentCount()];
            for (int i = 0; i < arguments.length; i++) {
                arguments[i] = constant(node, helpers, dynamic.getBootstrapMethodArgument(i));
            }
            var bootstrap = (Handle) constant(node, helpers, dynamic.getBootstrapMethod());
            return new ConstantDynamic(dynamic.getName(), dynamic.getDescriptor(), bootstrap, arguments);
        }
        return value;
    }

    /** A method handle cannot point at a call site, so it points at a private static forwarder instead. */
    private static Handle helperHandle(ClassNode node, List<MethodNode> helpers, Handle handle) {
        if (handle.getTag() != Opcodes.H_INVOKESTATIC) throw new IllegalArgumentException("Nonstatic backend handle");
        checkDescriptor(handle.getDesc());
        String name = HELPER_PREFIX + helpers.size();
        if (node.methods.stream().anyMatch(m -> m.name.equals(name))) {
            throw new IllegalArgumentException("Reserved Julia helper name: " + name);
        }
        var helper = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, name, handle.getDesc(), null, null);
        int slot = 0;
        for (var argument : Type.getArgumentTypes(handle.getDesc())) {
            helper.instructions.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), slot));
            slot += argument.getSize();
        }
        helper.instructions.add(dynamic(handle.getOwner(), handle.getName(), handle.getDesc()));
        var result = Type.getReturnType(handle.getDesc());
        helper.instructions.add(new InsnNode(result.getOpcode(Opcodes.IRETURN)));
        helper.maxLocals = slot;
        helper.maxStack = Math.max(slot, result.getSize());
        helpers.add(helper);
        boolean onInterface = (node.access & Opcodes.ACC_INTERFACE) != 0;
        return new Handle(Opcodes.H_INVOKESTATIC, node.name, name, handle.getDesc(), onInterface);
    }
}
