package dev.fopwoc.julia.transform.pass;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** ZombieBuddy's synchronous approval loop draws outside PZ's normal sprite-frame owner. */
public final class ApprovalPass {
    static final String FRONTEND = "me/zed_0xff/zombie_buddy/frontend/ImguiModApprovalFrontend";
    static final String CONTEXT = FRONTEND + "$OwnedImguiContext";
    static final String DIALOG = "me/zed_0xff/zombie_buddy/frontend/ImguiApprovalDialog";

    private ApprovalPass() {}

    public static void adapt(ClassNode node) {
        if (node.name.equals(FRONTEND)) frames(node);
        else if (node.name.equals(CONTEXT)) dimensions(node);
    }

    private static void frames(ClassNode node) {
        MethodNode frame = null;
        MethodInsnNode draw = null, swap = null;
        for (var method : node.methods) {
            MethodInsnNode candidateDraw = null, candidateSwap = null;
            for (var instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                if (call.owner.equals("dev/fopwoc/julia/runtime/FrameHooks"))
                    throw new IllegalArgumentException("Approval frame already transformed");
                if (call.owner.equals(CONTEXT)
                        && call.name.equals("render")
                        && call.desc.equals("(L" + DIALOG + ";)V")) {
                    if (candidateDraw != null || call.getOpcode() != Opcodes.INVOKEVIRTUAL)
                        throw new IllegalArgumentException("Ambiguous approval draw anchor");
                    candidateDraw = call;
                }
                if (call.owner.equals("org/lwjglx/opengl/Display")
                        && call.name.equals("update")
                        && call.desc.equals("(Z)V")) {
                    if (candidateSwap != null || candidateDraw == null || call.getOpcode() != Opcodes.INVOKESTATIC)
                        throw new IllegalArgumentException("Ambiguous approval present anchor");
                    candidateSwap = call;
                }
            }
            if (candidateDraw == null && candidateSwap == null) continue;
            if (frame != null
                    || candidateDraw == null
                    || candidateSwap == null
                    || (method.access & Opcodes.ACC_STATIC) == 0)
                throw new IllegalArgumentException("Expected one complete static approval frame");
            frame = method;
            draw = candidateDraw;
            swap = candidateSwap;
        }
        if (frame == null) throw new IllegalArgumentException("Missing approval frame anchors");
        FramePass.bracket(frame, draw, swap);
    }

    private static void dimensions(ClassNode node) {
        int queries = 0;
        for (var method : node.methods)
            for (var instruction : method.instructions)
                if (instruction instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals("org/lwjgl/glfw/GLFW")
                        && call.name.equals("glfwGetFramebufferSize")
                        && call.desc.equals("(J[I[I)V")) {
                    // Julia's game target uses logical pixels; its final presentation handles Retina scaling.
                    call.name = "glfwGetWindowSize";
                    queries++;
                }
        if (queries != 2)
            throw new IllegalArgumentException(
                    "Expected approval IO and font framebuffer-size queries, got " + queries);
    }
}
