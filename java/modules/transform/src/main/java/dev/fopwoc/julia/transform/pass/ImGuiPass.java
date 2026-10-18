package dev.fopwoc.julia.transform.pass;

import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Exposes the host handoff to the prepared renderer without sending ImGui objects across runtime isolation. */
public final class ImGuiPass {
    public static final String RENDERER = "imgui/gl3/ImGuiImplGl3";
    private static final String DISPLAY = "org/lwjglx/opengl/Display";
    private static final String DRAW = "(Limgui/ImDrawData;)V";
    private static final String HELPER = "julia$drawImGuiDrawData";

    private ImGuiPass() {}

    public static void adapt(ClassNode node) {
        boolean changed = false;
        for (var method : node.methods) {
            for (var instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)
                        || call.getOpcode() != Opcodes.INVOKESTATIC
                        || !call.owner.equals(DISPLAY)
                        || !call.name.equals("drawImGuiDrawData")
                        || !call.desc.equals(DRAW)) continue;
                if (!changed && node.methods.stream().anyMatch(candidate -> candidate.name.equals(HELPER)))
                    throw new IllegalArgumentException("Reserved Julia helper name: " + HELPER);
                call.owner = node.name;
                call.name = HELPER;
                call.itf = (node.access & Opcodes.ACC_INTERFACE) != 0;
                changed = true;
            }
        }
        if (changed) node.methods.add(helper());
    }

    /** The archive gate must include this consumer; retaining an unprepared host renderer would reenter native GL. */
    public static Set<String> requiredConsumers(ClassNode node) {
        for (var method : node.methods)
            for (var instruction : method.instructions)
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals(RENDERER)
                        && call.name.equals("renderDrawData")
                        && call.desc.equals(DRAW)) return Set.of(RENDERER);
        return Set.of();
    }

    private static MethodNode helper() {
        var method = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, HELPER, DRAW, null, null);
        var draw = new LabelNode();
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "zombie/core/Core", "isImGui", "()Z", false));
        method.instructions.add(new JumpInsnNode(Opcodes.IFNE, draw));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.instructions.add(draw);
        method.instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, DISPLAY, "imGuiGl3", "L" + RENDERER + ";"));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, RENDERER, "renderDrawData", DRAW, false));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "imgui/ImGui", "freeDrawData", DRAW, false));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 2;
        method.maxLocals = 1;
        return method;
    }
}
