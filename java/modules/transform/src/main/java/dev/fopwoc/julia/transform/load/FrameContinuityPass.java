package dev.fopwoc.julia.transform.load;

import dev.fopwoc.julia.transform.Endpoint;
import dev.fopwoc.julia.transform.asm.Anchors;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** A replacement command buffer within the same frame must load the existing default-framebuffer contents. */
public final class FrameContinuityPass implements UpstreamPatch {

    @Override
    public boolean targets(String name) {
        return name.equals("com.gtnewhorizons.angelica.sdlgpu.frame.FrameManager");
    }

    @Override
    public boolean apply(String name, ClassNode node) {
        int changed = 0;
        for (var method : node.methods) {
            if (!method.name.equals("reacquireCommandBuffer") || !method.desc.equals("()V")) continue;
            for (var instruction : method.instructions.toArray()) {
                if (instruction.getOpcode() == Opcodes.RETURN)
                    method.instructions.insertBefore(
                            instruction,
                            new MethodInsnNode(
                                    Opcodes.INVOKESTATIC,
                                    Endpoint.COMMAND_BUFFER_STATE.owner(),
                                    "reacquired",
                                    "()V",
                                    false));
                if (!(instruction instanceof FieldInsnNode field)
                        || field.getOpcode() != Opcodes.PUTFIELD
                        || !field.owner.equals("com/gtnewhorizons/angelica/sdlgpu/frame/FrameManager$FrameState")
                        || !field.name.equals("clearedThisFrame")
                        || !field.desc.equals("Z")) continue;
                var value = Anchors.previous(field);
                if (value == null || value.getOpcode() != Opcodes.ICONST_0)
                    throw new IllegalStateException("Unexpected mid-frame clear-state assignment");
                // Consume the same object and boolean operands without resetting attachment history.
                method.instructions.set(field, new InsnNode(Opcodes.POP2));
                changed++;
            }
        }
        Anchors.require("mid-frame clear reset", changed, 1);
        return true;
    }
}
