package dev.fopwoc.julia.transform.load;

import dev.fopwoc.julia.transform.Endpoint;
import dev.fopwoc.julia.transform.asm.Anchors;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * Per-output blend equations: pipeline creation reads each colour target's ops through Julia, the pipeline key mixes
 * them in, and GLSM's global equation cache is bypassed while outputs differ.
 */
public final class BlendEquationPass implements UpstreamPatch {
    private static final String PIPELINE = "com.gtnewhorizons.angelica.sdlgpu.pipeline.PipelineCache";
    private static final String GLSM = "com.gtnewhorizons.angelica.glsm.GLStateManager";

    @Override
    public boolean targets(String name) {
        return name.equals(PIPELINE) || name.equals(GLSM);
    }

    @Override
    public boolean apply(String name, ClassNode node) {
        int edits = 0;
        for (var method : node.methods) {
            if (name.equals(GLSM)
                    && ((method.name.equals("glBlendEquation") && method.desc.equals("(I)V"))
                            || (method.name.equals("glBlendEquationSeparate") && method.desc.equals("(II)V"))))
                edits += Anchors.replaceCalls(
                        method, GLSM.replace('.', '/'), "isCachingEnabled", "()Z", () -> hook("cacheEnabled", "()Z"));
            if (name.equals(PIPELINE) && method.name.equals("computeOutputHash") && method.desc.equals("()J"))
                for (var instruction : method.instructions.toArray())
                    if (instruction.getOpcode() == Opcodes.LRETURN) {
                        method.instructions.insertBefore(instruction, hook("hash", "(J)J"));
                        edits++;
                    }
            if (name.equals(PIPELINE) && method.name.equals("createPipelineInner")) {
                int slot = colorTargetSlot(node.name, method);
                for (var instruction : method.instructions.toArray())
                    if (instruction instanceof FieldInsnNode field
                            && field.getOpcode() == Opcodes.GETFIELD
                            && field.owner.equals(node.name)
                            && (field.name.equals("colorBlendOp") || field.name.equals("alphaBlendOp"))) {
                        var lookup = new InsnList();
                        lookup.add(new VarInsnNode(Opcodes.ILOAD, slot));
                        lookup.add(hook(field.name.equals("colorBlendOp") ? "color" : "alpha", "(II)I"));
                        method.instructions.insert(field, lookup);
                        edits++;
                    }
            }
        }
        Anchors.require("blend-equation integration in " + name, edits, name.equals(PIPELINE) ? 3 : 2);
        return true;
    }

    /** The colour-target loop index, as the per-slot blend-enable lookup loads it. */
    private static int colorTargetSlot(String owner, MethodNode method) {
        for (var instruction : method.instructions)
            if (Anchors.isCall(instruction, owner, "blendEnabledForSlot", "(I)Z")
                    && Anchors.previous(instruction) instanceof VarInsnNode load
                    && load.getOpcode() == Opcodes.ILOAD) return load.var;
        throw new IllegalStateException("Pipeline color-target loop changed");
    }

    private static MethodInsnNode hook(String name, String descriptor) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, Endpoint.BLEND_EQUATION_HOOKS.owner(), name, descriptor, false);
    }
}
