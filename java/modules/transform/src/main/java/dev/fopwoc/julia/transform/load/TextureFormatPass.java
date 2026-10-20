package dev.fopwoc.julia.transform.load;

import static org.lwjgl.opengl.GL30.GL_R16;
import static org.lwjgl.sdl.SDLGPU.SDL_GPU_TEXTUREFORMAT_R16_UNORM;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Extend the pinned format lookup so mutable R16 storage cannot silently become RGBA8. */
public final class TextureFormatPass {
    private TextureFormatPass() {}

    public static byte[] adapt(String name, byte[] bytes) {
        if (!name.equals("com.gtnewhorizons.angelica.sdlgpu.resource.ResourceManager")) return bytes;
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        int edits = 0;
        for (var method : node.methods)
            if (method.name.equals("lookupTextureFormat") && method.desc.equals("(III)I")) {
                var head = new InsnList();
                var next = new LabelNode();
                head.add(new VarInsnNode(Opcodes.ILOAD, 0));
                head.add(new LdcInsnNode(GL_R16));
                head.add(new JumpInsnNode(Opcodes.IF_ICMPNE, next));
                head.add(new LdcInsnNode(SDL_GPU_TEXTUREFORMAT_R16_UNORM));
                head.add(new InsnNode(Opcodes.IRETURN));
                head.add(next);
                head.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                method.instructions.insert(head);
                edits++;
            }
        if (edits != 1) throw new IllegalStateException("Pinned R16 texture format lookup changed: anchors=" + edits);
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
