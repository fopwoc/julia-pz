package dev.fopwoc.julia.transform.load;

import dev.fopwoc.julia.transform.Endpoint;
import dev.fopwoc.julia.transform.asm.Anchors;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Pinned integration points for ownership and sampler preparation inside the published backend. */
public final class TextureViewPass implements UpstreamPatch {
    private static final String ROOT = "com.gtnewhorizons.angelica.sdlgpu.";
    private static final Set<String> TARGETS = Set.of(
            "com.gtnewhorizons.angelica.glsm.GLStateManager",
            ROOT + "resource.ResourceManager",
            ROOT + "pipeline.PipelineApplier",
            ROOT + "sampler.SamplerBinder",
            ROOT + "resource.FBOClearTracker");
    private static final String RESOURCE = ROOT.replace('.', '/') + "resource/ResourceManager";
    private static final String STATE = ROOT.replace('.', '/') + "frame/ContextState";

    @Override
    public boolean targets(String name) {
        return TARGETS.contains(name);
    }

    @Override
    public boolean apply(String name, ClassNode node) {
        boolean glsm = name.equals("com.gtnewhorizons.angelica.glsm.GLStateManager");
        boolean resource = name.equals(ROOT + "resource.ResourceManager");
        boolean pipeline = name.equals(ROOT + "pipeline.PipelineApplier");
        boolean sampler = name.equals(ROOT + "sampler.SamplerBinder");
        boolean clears = name.equals(ROOT + "resource.FBOClearTracker");
        int edits = 0;
        for (var method : node.methods) {
            if (clears
                    && ((method.name.equals("materializePendingClearForTexture")
                                    && method.desc.equals("(L" + STATE + ";J)V"))
                            || (method.name.equals("resolveDestinationForWrite")
                                    && method.desc.equals(
                                            "(L" + STATE + ";JL" + RESOURCE + "$TextureMeta;IIIIIIZ)V")))) {
                var head = new InsnList();
                head.add(new VarInsnNode(Opcodes.LLOAD, 2));
                head.add(call("sharedAccess", "(J)V"));
                method.instructions.insert(head);
                edits++;
            }
            if (glsm && method.name.equals("deferDeleteTexture") && method.desc.equals("(I)V")) {
                method.instructions.clear();
                method.tryCatchBlocks.clear();
                if (method.localVariables != null) method.localVariables.clear();
                method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
                method.instructions.add(call("delete", "(I)V"));
                method.instructions.add(new InsnNode(Opcodes.RETURN));
                edits++;
            }
            if (resource
                    && method.name.equals("releaseTextureHandleAfterPendingUploads")
                    && method.desc.equals("(J)V")) {
                var head = new InsnList();
                var proceed = new LabelNode();
                head.add(new VarInsnNode(Opcodes.ALOAD, 0));
                head.add(new VarInsnNode(Opcodes.LLOAD, 1));
                head.add(call("referenced", "(L" + RESOURCE + ";J)Z"));
                head.add(new JumpInsnNode(Opcodes.IFEQ, proceed));
                head.add(new InsnNode(Opcodes.RETURN));
                head.add(proceed);
                head.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                method.instructions.insert(head);
                edits++;
            }
            if (resource && method.name.equals("releaseTextureHandleForRealloc") && method.desc.equals("(I)V")) {
                var head = new InsnList();
                head.add(new VarInsnNode(Opcodes.ILOAD, 1));
                head.add(call("beforeAllocation", "(I)V"));
                method.instructions.insert(head);
                edits++;
            }
            if (resource && method.name.equals("createTextureCore") && method.desc.equals("(IIIIIIIII)J")) {
                var head = new InsnList();
                head.add(new VarInsnNode(Opcodes.ILOAD, 1));
                head.add(call("beforeAllocation", "(I)V"));
                method.instructions.insert(head);
                edits++;
            }
            if (pipeline
                    && method.name.equals("ensureDrawRenderPass")
                    && method.desc.equals(
                            "(L" + STATE + ";L" + ROOT.replace('.', '/') + "frame/FrameManager$FrameState;)V")) {
                var head = new InsnList();
                head.add(new VarInsnNode(Opcodes.ALOAD, 1));
                head.add(call("prepare", "(L" + STATE + ";)V"));
                method.instructions.insert(head);
                edits++;
            }
            if (sampler)
                for (var instruction : method.instructions.toArray()) {
                    if (instruction instanceof MethodInsnNode invoke
                            && invoke.owner.equals(RESOURCE)
                            && invoke.name.equals("getTextureHandle")
                            && invoke.desc.equals("(I)J")) {
                        method.instructions.set(invoke, call("samplingHandle", "(L" + RESOURCE + ";I)J"));
                        edits++;
                    }
                }
        }
        Anchors.require("texture-view integration in " + name, edits, resource ? 3 : clears ? 2 : 1);
        return true;
    }

    private static MethodInsnNode call(String name, String descriptor) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, Endpoint.TEXTURE_VIEW_HOOKS.owner(), name, descriptor, false);
    }
}
