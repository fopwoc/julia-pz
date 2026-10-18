package dev.julia.probe.redirect;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Only the exact observed resource overloads enter the bounded implementations. */
public final class ResourceBindings {
    public static final String BUFFER = "dev/julia/probe/adapter/BufferStorageBridge";
    public static final String CLEAR = "dev/julia/probe/adapter/TextureClearBridge";
    public static final String COPY = "dev/julia/probe/adapter/TextureCopyBridge";
    private ResourceBindings() { }
    public static void adapt(ClassNode node) {
        for (var method : node.methods) {
            for (var instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC) continue;
                if (call.owner.equals("org/lwjgl/opengl/GL45") && call.name.equals("glNamedBufferStorage") && call.desc.equals("(ILjava/nio/ByteBuffer;I)V")) call.owner = BUFFER;
                if (call.owner.equals("com/gtnewhorizons/angelica/glsm/GLStateManager") && call.name.equals("glCopyImageSubData") && call.desc.equals("(IIIIIIIIIIIIIII)V")) call.owner = COPY;
                if (call.owner.equals("org/lwjgl/opengl/GL44") && call.name.equals("glClearTexSubImage") && call.desc.equals("(IIIIIIIIII[F)V")) call.owner = CLEAR;
            }
        }
    }
}
