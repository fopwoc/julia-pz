package dev.fopwoc.julia.transform.load;

import dev.fopwoc.julia.transform.Endpoint;
import dev.fopwoc.julia.transform.asm.Anchors;
import java.util.Set;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Pinned framebuffer slice metadata participates in render-pass creation and structural equivalence. */
public final class FramebufferPass implements UpstreamPatch {
    private static final String ROOT = "com.gtnewhorizons.angelica.sdlgpu.";
    private static final Set<String> TARGETS = Set.of(
            ROOT + "resource.FboState",
            ROOT + "resource.FBOClearTracker",
            ROOT + "pipeline.PipelineApplier",
            ROOT + "frame.FrameManager",
            ROOT + "pipeline.PipelineCache");
    private static final String FBO = ROOT.replace('.', '/') + "resource/FboState";
    private static final String FRAMES = ROOT.replace('.', '/') + "frame/FrameManager";
    private static final String RESOURCE = ROOT.replace('.', '/') + "resource/ResourceManager";
    private static final String CONTEXT = ROOT.replace('.', '/') + "frame/ContextState";
    private static final String TARGETS_ARGS =
            "Lorg/lwjgl/sdl/SDL_GPUColorTargetInfo$Buffer;Lorg/lwjgl/sdl/SDL_GPUDepthStencilTargetInfo;";

    @Override
    public boolean targets(String name) {
        return TARGETS.contains(name);
    }

    @Override
    public boolean apply(String name, ClassNode node) {
        boolean fbo = name.equals(ROOT + "resource.FboState"),
                clears = name.equals(ROOT + "resource.FBOClearTracker"),
                pipeline = name.equals(ROOT + "pipeline.PipelineApplier");
        boolean frames = name.equals(ROOT + "frame.FrameManager");
        boolean cache = name.equals(ROOT + "pipeline.PipelineCache");
        int edits = 0;
        for (var method : node.methods) {
            if (cache && method.name.equals("setColorTargetFormats") && method.desc.equals("([I)V")) {
                Anchors.require(
                        "pipeline color format field",
                        (int) node.fields.stream()
                                .filter(field -> field.name.equals("colorTargetFormats") && field.desc.equals("[I"))
                                .count(),
                        1);
                // null selects the swapchain; an empty array is a real depth-only framebuffer.
                method.instructions.clear();
                method.tryCatchBlocks.clear();
                if (method.localVariables != null) method.localVariables.clear();
                method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
                method.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, "colorTargetFormats", "[I"));
                method.instructions.add(new InsnNode(Opcodes.RETURN));
                edits++;
            }
            if (frames && method.name.equals("beginRenderPass")) {
                edits += Anchors.replaceCalls(
                        method,
                        "org/lwjgl/sdl/SDLGPU",
                        "SDL_BeginGPURenderPass",
                        "(J" + TARGETS_ARGS + ")J",
                        () -> call("beginNative", "(J" + TARGETS_ARGS + ")J"));
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
                edits += Anchors.replaceCalls(
                        method,
                        RESOURCE,
                        "getOrCreateDummyColorTarget",
                        "()J",
                        () -> call("unusedTarget", "(L" + RESOURCE + ";)J"));
                edits += Anchors.replaceCalls(
                        method,
                        FRAMES,
                        "beginRenderPass",
                        "(" + TARGETS_ARGS + ")J",
                        () -> call("begin", "(L" + FRAMES + ";" + TARGETS_ARGS + ")J"));
            }
        }
        Anchors.require("framebuffer slice integration in " + name, edits, pipeline ? 3 : clears ? 2 : 1);
        return true;
    }

    private static MethodInsnNode call(String name, String desc) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, Endpoint.FRAMEBUFFER_HOOKS.owner(), name, desc, false);
    }
}
