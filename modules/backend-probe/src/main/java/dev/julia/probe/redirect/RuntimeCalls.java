package dev.julia.probe.redirect;

import java.util.ArrayList;
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

/** Replaces backend calls with typed JDK-only call sites, without linking the game to isolated LWJGL types. */
public final class RuntimeCalls {
    private static final Handle BOOTSTRAP = new Handle(Opcodes.H_INVOKESTATIC,
            "dev/julia/runtime/RenderDispatch", "bootstrap",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;)Ljava/lang/invoke/CallSite;", false);

    private RuntimeCalls() { }

    static boolean target(String owner) {
        return owner.equals("com/gtnewhorizons/angelica/glsm/GLStateManager")
                || owner.equals("com/gtnewhorizons/angelica/sdlgpu/compat/AwareGLDebugShim")
                || owner.equals(UniformBindings.OWNER) || owner.equals(TextureAllocationBindings.OWNER) || owner.equals(CapabilityBindings.OWNER);
    }

    public static int adapt(ClassNode node) {
        var helpers = new ArrayList<MethodNode>();
        int count = 0;
        for (var method : node.methods) {
            for (var instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode call && target(call.owner)) {
                    if (call.getOpcode() != Opcodes.INVOKESTATIC) throw new IllegalArgumentException("Nonstatic backend call");
                    checkDescriptor(call.desc);
                    method.instructions.set(call, dynamic(call.owner, call.name, call.desc));
                    count++;
                } else if (instruction instanceof LdcInsnNode constant) {
                    constant.cst = constant(node, helpers, constant.cst);
                } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                    dynamic.bsm = (Handle) constant(node, helpers, dynamic.bsm);
                    for (int i = 0; i < dynamic.bsmArgs.length; i++) dynamic.bsmArgs[i] = constant(node, helpers, dynamic.bsmArgs[i]);
                }
            }
        }
        node.methods.addAll(helpers);
        return count + helpers.size();
    }

    private static InvokeDynamicInsnNode dynamic(String owner, String name, String descriptor) {
        return new InvokeDynamicInsnNode(name, descriptor, BOOTSTRAP, owner);
    }

    private static Object constant(ClassNode node, ArrayList<MethodNode> helpers, Object value) {
        if (value instanceof Handle handle && target(handle.getOwner())) {
            if (handle.getTag() != Opcodes.H_INVOKESTATIC) throw new IllegalArgumentException("Nonstatic backend handle");
            checkDescriptor(handle.getDesc());
            String name = "julia$dispatch$" + helpers.size();
            if (node.methods.stream().anyMatch(m -> m.name.equals(name))) throw new IllegalArgumentException("Reserved Julia helper name: " + name);
            var helper = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, name, handle.getDesc(), null, null);
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
            return new Handle(Opcodes.H_INVOKESTATIC, node.name, name, handle.getDesc(), (node.access & Opcodes.ACC_INTERFACE) != 0);
        }
        if (value instanceof ConstantDynamic dynamic) {
            Object[] arguments = new Object[dynamic.getBootstrapMethodArgumentCount()];
            for (int i = 0; i < arguments.length; i++) arguments[i] = constant(node, helpers, dynamic.getBootstrapMethodArgument(i));
            return new ConstantDynamic(dynamic.getName(), dynamic.getDescriptor(),
                    (Handle) constant(node, helpers, dynamic.getBootstrapMethod()), arguments);
        }
        return value;
    }

    static void checkDescriptor(String descriptor) {
        checkType(Type.getReturnType(descriptor));
        for (var argument : Type.getArgumentTypes(descriptor)) checkType(argument);
    }

    private static void checkType(Type type) {
        if (type.getSort() == Type.ARRAY) { checkType(type.getElementType()); return; }
        if (type.getSort() == Type.OBJECT && !type.getInternalName().startsWith("java/")) {
            throw new IllegalArgumentException("Non-JDK type crosses graphics runtime boundary: " + type.getDescriptor());
        }
    }
}
