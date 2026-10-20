package dev.fopwoc.julia.transform.load;

import dev.fopwoc.julia.transform.Endpoint;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Pinned framebuffer slice metadata participates in render-pass creation and structural equivalence. */
public final class FramebufferPass {
    private static final String ROOT = "com.gtnewhorizons.angelica.sdlgpu.";
    private static final String FBO = ROOT.replace('.', '/') + "resource/FboState";
    private static final String FRAMES = ROOT.replace('.', '/') + "frame/FrameManager";
    private static final String RESOURCE = ROOT.replace('.', '/') + "resource/ResourceManager";
    private static final String CONTEXT = ROOT.replace('.', '/') + "frame/ContextState";

    private FramebufferPass() {}

    public static byte[] adapt(String name, byte[] bytes) {
        boolean fbo = name.equals(ROOT + "resource.FboState"),
                clears = name.equals(ROOT + "resource.FBOClearTracker"),
                pipeline = name.equals(ROOT + "pipeline.PipelineApplier");
        boolean frames = name.equals(ROOT + "frame.FrameManager");
        if (!fbo && !clears && !pipeline && !frames) return bytes;
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        int edits = 0;
        for (var method : node.methods) {
            if (frames && method.name.equals("beginRenderPass")) {
                for (var instruction : method.instructions.toArray())
                    if (instruction instanceof MethodInsnNode invoke
                            && invoke.owner.equals("org/lwjgl/sdl/SDLGPU")
                            && invoke.name.equals("SDL_BeginGPURenderPass")) {
                        method.instructions.set(invoke, call("beginNative", invoke.desc));
                        edits++;
                    }
            }
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
            if (clears && method.name.equals("updatePipelineCacheColorFormats")) {
                method.instructions.clear();
                method.tryCatchBlocks.clear();
                if (method.localVariables != null) method.localVariables.clear();
                method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
                method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
                method.instructions.add(call("formats", "(L" + CONTEXT + ";L" + FBO + ";)V"));
                method.instructions.add(new InsnNode(Opcodes.RETURN));
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
                for (var instruction : method.instructions.toArray()) {
                    if (instruction instanceof MethodInsnNode dummy
                            && dummy.owner.equals(RESOURCE)
                            && dummy.name.equals("getOrCreateDummyColorTarget")
                            && dummy.desc.equals("()J")) {
                        method.instructions.set(dummy, call("unusedTarget", "(L" + RESOURCE + ";)J"));
                        edits++;
                    }
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
        }
        if (edits != (pipeline ? 3 : clears ? 2 : 1))
            throw new IllegalStateException("Framebuffer slice integration changed: " + name + " anchors=" + edits);
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static MethodInsnNode call(String name, String desc) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, Endpoint.FRAMEBUFFER_HOOKS.owner(), name, desc, false);
    }
}
