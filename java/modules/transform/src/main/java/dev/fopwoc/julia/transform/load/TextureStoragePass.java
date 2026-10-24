package dev.fopwoc.julia.transform.load;

import dev.fopwoc.julia.transform.Endpoint;
import dev.fopwoc.julia.transform.asm.Anchors;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * Reports every upstream write to the texture handle and metadata maps, so Julia's per-draw caches of derived texture
 * state know when to refresh. Signalled on entry and before every return: another thread may change storage under
 * upstream's lock while the render thread refills a cache.
 */
public final class TextureStoragePass implements UpstreamPatch {
    private static final String RESOURCE = "com.gtnewhorizons.angelica.sdlgpu.resource.ResourceManager";
    private static final String META = "Lcom/gtnewhorizons/angelica/sdlgpu/resource/ResourceManager$TextureMeta;";
    private static final Set<String> WRITERS = Set.of(
            "createTextureCore(IIIIIIIII)J",
            "deleteTexture(I)V",
            "releaseTextureHandleForRealloc(I)V",
            "restoreTextureHandle(IJ" + META + ")V");

    @Override
    public boolean targets(String name) {
        return name.equals(RESOURCE);
    }

    @Override
    public boolean apply(String name, ClassNode node) {
        int edits = 0;
        for (var method : node.methods) {
            if (!WRITERS.contains(method.name + method.desc)) continue;
            for (var instruction : method.instructions.toArray())
                if (instruction.getOpcode() >= Opcodes.IRETURN && instruction.getOpcode() <= Opcodes.RETURN)
                    method.instructions.insertBefore(instruction, changed());
            method.instructions.insert(changed());
            edits++;
        }
        Anchors.require("texture storage writers in " + name, edits, WRITERS.size());
        return true;
    }

    private static MethodInsnNode changed() {
        return new MethodInsnNode(
                Opcodes.INVOKESTATIC, Endpoint.TEXTURE_VIEW_HOOKS.owner(), "storageChanged", "()V", false);
    }
}
