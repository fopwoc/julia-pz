package dev.fopwoc.julia.transform.load;

import dev.fopwoc.julia.transform.Endpoint;
import dev.fopwoc.julia.transform.asm.Anchors;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Adds indexed output masks to the published pipeline descriptor/key and invalidates GLSM's global cache. */
public final class ColorMaskPass implements UpstreamPatch {
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
            if (name.equals(GLSM) && method.name.equals("glColorMask") && method.desc.equals("(ZZZZ)V"))
                edits += Anchors.replaceCalls(
                        method, GLSM.replace('.', '/'), "isCachingEnabled", "()Z", () -> hook("cacheEnabled", "()Z"));
            if (name.equals(PIPELINE) && method.name.equals("computeOutputHash") && method.desc.equals("()J")) {
                for (var instruction : method.instructions.toArray())
                    if (instruction.getOpcode() == Opcodes.LRETURN) {
                        method.instructions.insertBefore(instruction, hook("hash", "(J)J"));
                        edits++;
                    }
            }
            if (name.equals(PIPELINE) && method.name.equals("createPipelineInner")) {
                int slot = -1;
                for (var instruction : method.instructions)
                    if (instruction instanceof MethodInsnNode call
                            && call.name.equals("blendEnabledForSlot")
                            && call.desc.equals("(I)Z")
                            && Anchors.previous(call) instanceof VarInsnNode load
                            && load.getOpcode() == Opcodes.ILOAD) slot = load.var;
                if (slot < 0) throw new IllegalStateException("Pipeline color-target loop changed");
                for (var instruction : method.instructions.toArray())
                    if (instruction instanceof FieldInsnNode field
                            && field.getOpcode() == Opcodes.GETFIELD
                            && field.owner.equals(PIPELINE.replace('.', '/'))
                            && field.name.equals("colorWriteMask")) {
                        var lookup = new InsnList();
                        lookup.add(new InsnNode(Opcodes.POP));
                        lookup.add(new VarInsnNode(Opcodes.ILOAD, slot));
                        lookup.add(hook("mask", "(I)I"));
                        method.instructions.insertBefore(field, lookup);
                        method.instructions.remove(field);
                        edits++;
                    }
            }
        }
        Anchors.require("indexed color-mask integration in " + name, edits, name.equals(PIPELINE) ? 2 : 1);
        return true;
    }

    private static MethodInsnNode hook(String name, String descriptor) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, Endpoint.COLOR_MASK_HOOKS.owner(), name, descriptor, false);
    }
}
