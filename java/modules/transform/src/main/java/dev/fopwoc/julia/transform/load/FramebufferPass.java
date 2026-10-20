package dev.fopwoc.julia.transform.load;

import dev.fopwoc.julia.transform.Endpoint;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Pinned framebuffer slice metadata participates in render-pass creation and structural equivalence. */
public final class FramebufferPass {
    private static final String ROOT = "com.gtnewhorizons.angelica.sdlgpu.";
    private static final String FBO = ROOT.replace('.', '/') + "resource/FboState";
    private static final String FRAMES = ROOT.replace('.', '/') + "frame/FrameManager";
    private static final String CONTEXT = ROOT.replace('.', '/') + "frame/ContextState";

    private FramebufferPass() {}

    public static byte[] adapt(String name, byte[] bytes) {
        boolean fbo = name.equals(ROOT + "resource.FboState"),
                clears = name.equals(ROOT + "resource.FBOClearTracker"),
                pipeline = name.equals(ROOT + "pipeline.PipelineApplier");
        if (!fbo && !clears && !pipeline) return bytes;
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        int edits = 0;
        for (var method : node.methods) {
            if (fbo && method.name.equals("recomputeTargets"))
                for (var insn : method.instructions.toArray())
                    if (insn.getOpcode() == Opcodes.RETURN) {
                        var extra = new InsnList();
                        extra.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        extra.add(call("layout", "(L" + FBO + ";)V"));
                        method.instructions.insertBefore(insn, extra);
                        edits++;
                    }
            if (clears && method.name.equals("fbosHaveSameAttachments")) {
                var head = new InsnList();
                var next = new LabelNode();
                head.add(new VarInsnNode(Opcodes.ILOAD, 2));
                head.add(new VarInsnNode(Opcodes.ILOAD, 3));
                head.add(call("sameSlices", "(II)Z"));
                head.add(new JumpInsnNode(Opcodes.IFNE, next));
                head.add(new InsnNode(Opcodes.ICONST_0));
                head.add(new InsnNode(Opcodes.IRETURN));
                head.add(next);
                head.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                method.instructions.insert(head);
                edits++;
            }
            if (pipeline
                    && method.name.equals("ensureRenderPass")
                    && method.desc.equals("(L" + CONTEXT + ";L" + FRAMES + "$FrameState;Z)V")) {
                var head = new InsnList();
                head.add(new VarInsnNode(Opcodes.ALOAD, 1));
                head.add(call("validateDraw", "(L" + CONTEXT + ";)V"));
                method.instructions.insert(head);
                edits++;
                for (var instruction : method.instructions.toArray())
                    if (instruction instanceof MethodInsnNode invoke
                            && invoke.owner.equals(FRAMES)
                            && invoke.name.equals("beginRenderPass")
                            && invoke.desc.equals(
                                    "(Lorg/lwjgl/sdl/SDL_GPUColorTargetInfo$Buffer;Lorg/lwjgl/sdl/SDL_GPUDepthStencilTargetInfo;)J")) {
                        method.instructions.set(
                                invoke,
                                call(
                                        "begin",
                                        "(L" + FRAMES
                                                + ";Lorg/lwjgl/sdl/SDL_GPUColorTargetInfo$Buffer;Lorg/lwjgl/sdl/SDL_GPUDepthStencilTargetInfo;)J"));
                        edits++;
                    }
            }
        }
        if (edits != (pipeline ? 2 : 1))
            throw new IllegalStateException("Framebuffer slice integration changed: " + name + " anchors=" + edits);
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static MethodInsnNode call(String name, String desc) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, Endpoint.FRAMEBUFFER_HOOKS.owner(), name, desc, false);
    }
}
