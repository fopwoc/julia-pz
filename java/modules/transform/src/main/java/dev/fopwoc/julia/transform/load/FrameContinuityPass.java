package dev.fopwoc.julia.transform.load;

import dev.fopwoc.julia.transform.Endpoint;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** A replacement command buffer within the same frame must load the existing default-framebuffer contents. */
public final class FrameContinuityPass {
    private FrameContinuityPass() {}

    public static byte[] adapt(String name, byte[] bytes) {
        if (!name.equals("com.gtnewhorizons.angelica.sdlgpu.frame.FrameManager")) return bytes;
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
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
                var value = field.getPrevious();
                while (value != null && value.getOpcode() == -1) value = value.getPrevious();
                if (value == null || value.getOpcode() != Opcodes.ICONST_0)
                    throw new IllegalStateException("Unexpected mid-frame clear-state assignment");
                // Consume the same object and boolean operands without resetting attachment history.
                method.instructions.set(field, new InsnNode(Opcodes.POP2));
                changed++;
            }
        }
        if (changed != 1) throw new IllegalStateException("Expected one mid-frame clear reset, found " + changed);
        var writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
