package dev.fopwoc.julia.transform.pass;

import dev.fopwoc.julia.transform.Endpoint;
import dev.fopwoc.julia.transform.asm.Anchors;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** ZombieBuddy's synchronous approval loop draws outside PZ's normal sprite-frame owner. */
public final class ApprovalPass {
    static final String FRONTEND = "me/zed_0xff/zombie_buddy/frontend/ImguiModApprovalFrontend";
    static final String CONTEXT = FRONTEND + "$OwnedImguiContext";
    static final String DIALOG = "me/zed_0xff/zombie_buddy/frontend/ImguiApprovalDialog";
    // ImGui packs colors as ABGR: alpha 0.85, blue 0.7, green 0.85, red 0.65.
    private static final int STATUS_COLOR = 0xD9B3D9A6;

    private ApprovalPass() {}

    public static void adapt(ClassNode node) {
        if (node.name.equals(FRONTEND)) frames(node);
        else if (node.name.equals(CONTEXT)) {
            dimensions(node);
            status(node);
        }
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

    /**
     * Draws Julia's status line over the approval dialog, on ImGui's foreground list so ZombieBuddy's windows, layout
     * and input stay untouched. The line is cosmetic: if ZombieBuddy's frame changes shape, it is skipped, never fatal.
     */
    private static void status(ClassNode node) {
        for (var method : node.methods) {
            if (!method.name.equals("render") || !method.desc.equals("(L" + DIALOG + ";)V")) continue;
            for (var instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode render)
                        || !render.owner.equals("imgui/ImGui")
                        || !render.name.equals("render")
                        || !(Anchors.previous(render) instanceof MethodInsnNode draw)
                        || !draw.owner.equals(DIALOG)
                        || !draw.name.equals("draw")) continue;
                method.instructions.insertBefore(render, statusLine());
                method.maxStack = Math.max(method.maxStack, 5);
                return;
            }
        }
    }

    // Bottom-left, like the main-menu line: x = 12, y = display height - line height - 8, the menu's light green.
    private static InsnList statusLine() {
        var line = new InsnList();
        line.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, "imgui/ImGui", "getForegroundDrawList", "()Limgui/ImDrawList;", false));
        line.add(new LdcInsnNode(12f));
        line.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "imgui/ImGui", "getIO", "()Limgui/ImGuiIO;", false));
        line.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "imgui/ImGuiIO", "getDisplaySizeY", "()F", false));
        line.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "imgui/ImGui", "getTextLineHeight", "()F", false));
        line.add(new InsnNode(Opcodes.FSUB));
        line.add(new LdcInsnNode(8f));
        line.add(new InsnNode(Opcodes.FSUB));
        line.add(new LdcInsnNode(STATUS_COLOR));
        line.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, Endpoint.LUA_STATUS.owner(), "label", "()Ljava/lang/String;", false));
        line.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "imgui/ImDrawList", "addText", "(FFILjava/lang/String;)V", false));
        return line;
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
