package dev.fopwoc.julia.transform.pass;

import dev.fopwoc.julia.transform.Endpoint;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/**
 * Drops GLFW's GL buffer swaps from LWJGL2-compat {@code Display}. Julia presents through the GPU API, so a GL swap on
 * the same window either races Julia's swapchain (garbage frames under XWayland) or blocks forever, as it did in
 * ZombieBuddy's early approval loop on the Steam Deck. Swap interval requests are kept, since they're what the game's VSync
 * option comes down to, but handed to the runtime: there is no GL context here for GLFW to apply them to.
 *
 * <p>{@code Display} is a binding class that preparation leaves untouched, because its context-creation GL calls must
 * stay native. The host transformer applies only this pass to it, as the class loads.
 */
public final class SwapBuffersPass {
    public static final String DISPLAY = "org/lwjglx/opengl/Display";
    private static final String GLFW = "org/lwjgl/glfw/GLFW";

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
                        && call.owner.equals(GLFW)) {
                    if (call.name.equals("glfwSwapBuffers") && call.desc.equals("(J)V")) {
                        // The window handle stays on the stack; drop it instead of calling.
                        method.instructions.set(call, new InsnNode(Opcodes.POP2));
                        swaps++;
                    } else if (call.name.equals("glfwSwapInterval") && call.desc.equals("(I)V")) {
                        call.owner = Endpoint.PRESENT_INTERVAL.owner();
                        call.name = "set";
                    }
                }
        if (swaps == 0) throw new IllegalArgumentException("Expected GLFW buffer swaps in " + DISPLAY);
    }
}
