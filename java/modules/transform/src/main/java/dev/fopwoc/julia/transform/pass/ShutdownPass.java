package dev.fopwoc.julia.transform.pass;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Detaches the native backend on the context thread before GLFW destroys its window. */
public final class ShutdownPass {
    private ShutdownPass() {}

    public static void adapt(ClassNode node) {
        for (var method : node.methods)
            for (var instruction : method.instructions.toArray())
                if (instruction instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals("org/lwjglx/opengl/Display")
                        && call.name.equals("destroy")
                        && call.desc.equals("()V"))
                    method.instructions.insertBefore(
                            call,
                            new MethodInsnNode(
                                    Opcodes.INVOKESTATIC,
                                    "dev/fopwoc/julia/runtime/StartupHooks",
                                    "close",
                                    "()V",
                                    false));
    }
}
