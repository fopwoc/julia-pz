package dev.fopwoc.julia.transform.pass;

import java.util.Set;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Julia renders the borrowed window in logical pixels; native presentation applies the display's scale once. */
public final class FramebufferSizePass {
    private static final String GLFW = "org/lwjgl/glfw/GLFW";
    private static final String DISPLAY = "org/lwjglx/opengl/Display";
    private static final Set<String> OUTPUTS = Set.of("(J[I[I)V", "(JLjava/nio/IntBuffer;Ljava/nio/IntBuffer;)V");

    private FramebufferSizePass() {}

    public static void adapt(ClassNode node) {
        for (var method : node.methods)
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC) {
                    String replacement = replacement(call.owner, call.name, call.desc);
                    if (replacement != null) call.name = replacement;
                } else if (instruction instanceof LdcInsnNode constant && constant.cst instanceof Handle handle) {
                    constant.cst = reference(handle);
                } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                    for (int i = 0; i < dynamic.bsmArgs.length; i++)
                        if (dynamic.bsmArgs[i] instanceof Handle handle) dynamic.bsmArgs[i] = reference(handle);
                }
            }
    }

    private static Handle reference(Handle handle) {
        if (handle.getTag() != Opcodes.H_INVOKESTATIC) return handle;
        String replacement = replacement(handle.getOwner(), handle.getName(), handle.getDesc());
        return replacement == null
                ? handle
                : new Handle(handle.getTag(), handle.getOwner(), replacement, handle.getDesc(), handle.isInterface());
    }

    private static String replacement(String owner, String name, String descriptor) {
        if (owner.equals(DISPLAY) && (name.equals("getFramebufferWidth") || name.equals("getFramebufferHeight"))) {
            if (!descriptor.equals("()I"))
                throw new IllegalArgumentException("Unknown Display framebuffer-size signature: " + descriptor);
            return name.equals("getFramebufferWidth") ? "getWidth" : "getHeight";
        }
        if (!owner.equals(GLFW)) return null;
        if (name.equals("glfwGetFramebufferSize")) {
            if (!OUTPUTS.contains(descriptor))
                throw new IllegalArgumentException("Unknown GLFW framebuffer-size overload: " + descriptor);
            return "glfwGetWindowSize";
        }
        if (name.equals("nglfwGetFramebufferSize")) {
            if (!descriptor.equals("(JJJ)V"))
                throw new IllegalArgumentException("Unknown native GLFW framebuffer-size overload: " + descriptor);
            return "nglfwGetWindowSize";
        }
        return null;
    }
}
