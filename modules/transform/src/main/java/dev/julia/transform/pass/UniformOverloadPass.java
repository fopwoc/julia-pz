package dev.julia.transform.pass;

import dev.julia.transform.Endpoint;
import java.util.Set;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** LWJGL 3 uniform overloads missing from the published GLSM ABI, including method handles. */
public final class UniformOverloadPass {
    private static final String OWNER = Endpoint.UNIFORM_BRIDGE.owner();
    private static final String STATE_MANAGER = Endpoint.STATE_MANAGER.owner();
    private static final Set<String> METHODS = Set.of(
            "glUniform1(I[F)V", "glUniformMatrix4(IZ[F)V",
            "glGetActiveUniform(IIILjava/nio/IntBuffer;Ljava/nio/IntBuffer;)Ljava/lang/String;");

    private UniformOverloadPass() {}

    public static void adapt(ClassNode node) {
        for (var method : node.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && matches(call.owner, call.name, call.desc)) {
                    call.owner = OWNER;
                } else if (instruction instanceof LdcInsnNode constant) {
                    constant.cst = adaptConstant(constant.cst);
                } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                    dynamic.bsm = (Handle) adaptConstant(dynamic.bsm);
                    for (int i = 0; i < dynamic.bsmArgs.length; i++) {
                        dynamic.bsmArgs[i] = adaptConstant(dynamic.bsmArgs[i]);
                    }
                }
            }
        }
    }

    private static boolean matches(String owner, String name, String descriptor) {
        return owner.equals(STATE_MANAGER) && METHODS.contains(name + descriptor);
    }

    private static Object adaptConstant(Object value) {
        if (value instanceof Handle handle && matches(handle.getOwner(), handle.getName(), handle.getDesc())) {
            return new Handle(handle.getTag(), OWNER, handle.getName(), handle.getDesc(), handle.isInterface());
        }
        if (value instanceof ConstantDynamic dynamic) {
            Object[] arguments = new Object[dynamic.getBootstrapMethodArgumentCount()];
            for (int i = 0; i < arguments.length; i++) arguments[i] = adaptConstant(dynamic.getBootstrapMethodArgument(i));
            return new ConstantDynamic(dynamic.getName(), dynamic.getDescriptor(),
                    (Handle) adaptConstant(dynamic.getBootstrapMethod()), arguments);
        }
        return value;
    }
}
