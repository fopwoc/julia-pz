package dev.fopwoc.julia.transform.load;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;

/** Julia's inline texture writes and subsequent bound uploads must share one ordered command stream. */
public final class TextureUploadOrderPass {
    private static final String RESOURCE = "com.gtnewhorizons.angelica.sdlgpu.resource.ResourceManager";
    private static final String DESCRIPTOR = "(Lcom/gtnewhorizons/angelica/sdlgpu/frame/ContextState;I)Z";

    private TextureUploadOrderPass() {}

    public static byte[] adapt(String name, byte[] bytes) {
        if (!name.equals(RESOURCE)) return bytes;
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        int edits = 0;
        for (var method : node.methods) {
            if (!method.name.equals("shouldDeferTextureUpload") || !method.desc.equals(DESCRIPTOR)) continue;
            method.instructions.clear();
            method.tryCatchBlocks.clear();
            if (method.localVariables != null) method.localVariables.clear();
            method.instructions.add(new InsnNode(Opcodes.ICONST_0));
            method.instructions.add(new InsnNode(Opcodes.IRETURN));
            edits++;
        }
        if (edits != 1) throw new IllegalStateException("Pinned texture upload deferral hook changed: " + edits);
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
