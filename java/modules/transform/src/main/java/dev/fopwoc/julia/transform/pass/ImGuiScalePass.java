package dev.fopwoc.julia.transform.pass;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** ImGui draws into Julia's logical-pixel target; the final presenter applies the display's Retina scale. */
public final class ImGuiScalePass implements Opcodes {
    private ImGuiScalePass() {}

    public static void adapt(ClassNode node) {
        if (!node.name.equals(ImGuiPass.RENDERER)) return;
        int anchors = 0;
        for (var method : node.methods) {
            for (var instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)
                        || !call.owner.equals("imgui/ImDrawData")
                        || !call.name.equals("getFramebufferScale")
                        || !call.desc.equals("(Limgui/ImVec2;)V")
                        || call.getOpcode() != INVOKEVIRTUAL) continue;
                // Keep the destination vector and discard the draw-data receiver.
                var scale = new InsnList();
                scale.add(new InsnNode(SWAP));
                scale.add(new InsnNode(POP));
                scale.add(new InsnNode(DUP));
                scale.add(new InsnNode(FCONST_1));
                scale.add(new FieldInsnNode(PUTFIELD, "imgui/ImVec2", "x", "F"));
                scale.add(new InsnNode(FCONST_1));
                scale.add(new FieldInsnNode(PUTFIELD, "imgui/ImVec2", "y", "F"));
                method.instructions.insertBefore(call, scale);
                method.instructions.remove(call);
                anchors++;
            }
        }
        if (anchors != 1)
            throw new IllegalArgumentException("Expected one ImGui framebuffer-scale read, got " + anchors);
    }
}
