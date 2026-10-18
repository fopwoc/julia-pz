package dev.fopwoc.julia.transform.pass;

import dev.fopwoc.julia.transform.Endpoint;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Binds callback objects on the caller side; only a JDK method handle reaches the isolated endpoint. */
public final class DebugCallbackPass {
    public static final String CALLBACK_BINDER = "dev/fopwoc/julia/runtime/DebugCallback";
    private static final String CONTRACT = "org/lwjgl/opengl/GLDebugMessageCallbackI";
    private static final String ORIGINAL = "(L" + CONTRACT + ";J)V";

    private DebugCallbackPass() {}

    public static void adapt(ClassNode node) {
        for (var method : node.methods) {
            for (var instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)
                        || call.getOpcode() != Opcodes.INVOKESTATIC
                        || !call.name.equals("glDebugMessageCallback")
                        || !call.desc.equals(ORIGINAL)
                        || !(Endpoint.DEBUG_SHIM.is(call.owner)
                                || call.owner.equals("org/lwjgl/opengl/GL43")
                                || call.owner.equals("org/lwjgl/opengl/GL43C")
                                || call.owner.equals("org/lwjgl/opengl/KHRDebug"))) continue;
                var binding = new InsnList();
                // (callback, long) -> (long, callback) -> (long, handle) -> (handle, long).
                binding.add(new InsnNode(Opcodes.DUP2_X1));
                binding.add(new InsnNode(Opcodes.POP2));
                binding.add(new LdcInsnNode(Type.getObjectType(CONTRACT)));
                binding.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        CALLBACK_BINDER,
                        "bind",
                        "(Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;",
                        false));
                binding.add(new InsnNode(Opcodes.DUP_X2));
                binding.add(new InsnNode(Opcodes.POP));
                method.instructions.insertBefore(call, binding);
                call.owner = Endpoint.DEBUG_CALLBACK_BRIDGE.owner();
                call.desc = "(Ljava/lang/invoke/MethodHandle;J)V";
            }
        }
    }
}
