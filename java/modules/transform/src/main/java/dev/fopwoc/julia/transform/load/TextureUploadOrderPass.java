package dev.fopwoc.julia.transform.load;

import dev.fopwoc.julia.transform.asm.Anchors;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;

/** Julia's inline texture writes and subsequent bound uploads must share one ordered command stream. */
public final class TextureUploadOrderPass implements UpstreamPatch {
    private static final String RESOURCE = "com.gtnewhorizons.angelica.sdlgpu.resource.ResourceManager";
    private static final String DESCRIPTOR = "(Lcom/gtnewhorizons/angelica/sdlgpu/frame/ContextState;I)Z";

    @Override
    public boolean targets(String name) {
        return name.equals(RESOURCE);
    }

    @Override
    public boolean apply(String name, ClassNode node) {
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
        Anchors.require("texture upload deferral hook", edits, 1);
        return true;
    }
}
