package dev.fopwoc.julia.transform.pass;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/**
 * Drops GLFW's GL buffer swaps from LWJGL2-compat {@code Display}. Julia presents through the GPU API, so a GL swap on
 * the same window either races Julia's swapchain (garbage frames under XWayland) or blocks forever, as it did in
 * ZombieBuddy's early approval loop on the Steam Deck.
 *
 * <p>{@code Display} is a binding class that preparation leaves untouched, because its context-creation GL calls must
 * stay native. The host transformer applies only this pass to it, as the class loads.
 */
public final class SwapBuffersPass {
    public static final String DISPLAY = "org/lwjglx/opengl/Display";

    private SwapBuffersPass() {}

    public static byte[] stripSwaps(byte[] original) {
        var node = new ClassNode();
        new ClassReader(original).accept(node, 0);
        adapt(node);
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    public static void adapt(ClassNode node) {
        if (!node.name.equals(DISPLAY)) return;
        int swaps = 0;
        for (var method : node.methods)
            for (var instruction : method.instructions.toArray())
                if (instruction instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals("org/lwjgl/glfw/GLFW")
                        && call.name.equals("glfwSwapBuffers")
                        && call.desc.equals("(J)V")) {
                    // The window handle stays on the stack; drop it instead of calling.
                    method.instructions.set(call, new InsnNode(Opcodes.POP2));
                    swaps++;
                }
        if (swaps == 0) throw new IllegalArgumentException("Expected GLFW buffer swaps in " + DISPLAY);
    }
}
