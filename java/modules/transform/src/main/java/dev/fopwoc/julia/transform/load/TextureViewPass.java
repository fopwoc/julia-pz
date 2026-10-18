package dev.fopwoc.julia.transform.load;

import dev.fopwoc.julia.transform.Endpoint;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Pinned integration points for ownership and sampler preparation inside the published backend. */
public final class TextureViewPass {
    private static final String ROOT = "com.gtnewhorizons.angelica.sdlgpu.";
    private static final String RESOURCE = ROOT.replace('.', '/') + "resource/ResourceManager";
    private static final String STATE = ROOT.replace('.', '/') + "frame/ContextState";

    private TextureViewPass() {}

    public static byte[] adapt(String name, byte[] bytes) {
        boolean glsm = name.equals("com.gtnewhorizons.angelica.glsm.GLStateManager");
        boolean resource = name.equals(ROOT + "resource.ResourceManager");
        boolean pipeline = name.equals(ROOT + "pipeline.PipelineApplier");
        boolean sampler = name.equals(ROOT + "sampler.SamplerBinder");
        if (!glsm && !resource && !pipeline && !sampler) return bytes;
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        int edits = 0;
        for (var method : node.methods) {
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
        int expected = resource ? 3 : 1;
        if (edits != expected)
            throw new IllegalStateException(
                    "Texture-view integration changed: " + name + " has " + edits + " anchors, expected " + expected);
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static MethodInsnNode call(String name, String descriptor) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, Endpoint.TEXTURE_VIEW_HOOKS.owner(), name, descriptor, false);
    }
}
