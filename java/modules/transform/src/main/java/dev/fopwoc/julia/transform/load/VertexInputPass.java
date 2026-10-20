package dev.fopwoc.julia.transform.load;

import static org.lwjgl.opengl.GL11.GL_FLOAT;

import dev.fopwoc.julia.transform.Endpoint;
import dev.fopwoc.julia.transform.asm.Anchors;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * Full-width divisor hashing, native pipeline creation, typed generic attributes, and protection from stale legacy
 * layout repair.
 */
public final class VertexInputPass implements UpstreamPatch {
    private static final String ROOT = "com.gtnewhorizons.angelica.sdlgpu.";
    private static final Set<String> TARGETS = Set.of(
            ROOT + "pipeline.PipelineCache",
            "com.gtnewhorizons.angelica.glsm.GLStateManager",
            ROOT + "compute.VertexWriteReplayer");
    private static final String STATE = ROOT.replace('.', '/') + "frame/ContextState";
    private static final String CACHE = ROOT.replace('.', '/') + "pipeline/PipelineCache";

    @Override
    public boolean targets(String name) {
        return TARGETS.contains(name);
    }

    @Override
    public boolean apply(String name, ClassNode node) {
        boolean pipeline = name.equals(ROOT + "pipeline.PipelineCache");
        boolean glsm = name.equals("com.gtnewhorizons.angelica.glsm.GLStateManager");
        boolean replay = name.equals(ROOT + "compute.VertexWriteReplayer");
        int hashes = 0, creates = 0, protects = 0, guards = 0, defaults = 0;
        for (var method : node.methods) {
            if (glsm && method.name.equals("pointAttrib") && method.desc.endsWith(";I)V")) {
                var head = new InsnList();
                var next = new LabelNode();
                head.add(new VarInsnNode(Opcodes.ILOAD, 1));
                head.add(call("preserveLayout", "(I)Z"));
                head.add(new JumpInsnNode(Opcodes.IFEQ, next));
                head.add(new InsnNode(Opcodes.RETURN));
                head.add(next);
                head.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                method.instructions.insert(head);
                protects++;
            }
            for (var instruction : method.instructions.toArray()) {
                if (pipeline && method.name.equals("computeInputHash") && instruction.getOpcode() == Opcodes.LRETURN) {
                    var extra = new InsnList();
                    extra.add(new VarInsnNode(Opcodes.ALOAD, 2));
                    extra.add(call("pipelineHash", "(JL" + STATE + ";)J"));
                    method.instructions.insertBefore(instruction, extra);
                    hashes++;
                }
                if (pipeline
                        && instruction instanceof MethodInsnNode invoke
                        && invoke.owner.equals("org/lwjgl/sdl/SDLGPU")
                        && invoke.name.equals("SDL_CreateGPUGraphicsPipeline")
                        && invoke.desc.equals("(JLorg/lwjgl/sdl/SDL_GPUGraphicsPipelineCreateInfo;)J")) {
                    method.instructions.set(invoke, call("createPipeline", invoke.desc));
                    creates++;
                }
                if (pipeline
                        && method.name.equals("buildVertexInput")
                        && instruction instanceof MethodInsnNode invoke
                        && invoke.name.equals("mapVertexFormat")
                        && invoke.desc.equals("(IIZZ)I")
                        && Anchors.previous(invoke) instanceof InsnNode integer
                        && integer.getOpcode() == Opcodes.ICONST_0
                        && Anchors.previous(integer) instanceof InsnNode normalized
                        && normalized.getOpcode() == Opcodes.ICONST_0
                        && Anchors.previous(normalized) instanceof IntInsnNode type
                        && type.operand == GL_FLOAT) {
                    // Disabled attributes read the generic value; upstream declares it float even for integer inputs.
                    var location = new InsnList();
                    location.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    location.add(new VarInsnNode(Opcodes.ILOAD, attributeLocation(invoke)));
                    method.instructions.insertBefore(type, location);
                    method.instructions.remove(type);
                    method.instructions.remove(normalized);
                    method.instructions.remove(integer);
                    method.instructions.set(invoke, call("defaultFormat", "(IL" + CACHE + ";I)I"));
                    defaults++;
                }
                if (replay
                        && instruction instanceof MethodInsnNode invoke
                        && invoke.owner.equals("java/lang/Math")
                        && invoke.name.equals("min")
                        && invoke.desc.equals("(II)I")) {
                    var previous = Anchors.previous(invoke);
                    if (previous instanceof LdcInsnNode value
                            && Integer.valueOf(65535).equals(value.cst)) {
                        method.instructions.set(invoke, call("replayDivisor", "(II)I"));
                        guards++;
                    }
                }
            }
        }
        if (pipeline) {
            Anchors.require("pipeline input hashes", hashes, 3);
            Anchors.require("pipeline creation", creates, 1);
            Anchors.require("disabled attribute format", defaults, 1);
        }
        if (glsm) Anchors.require("legacy layout repair", protects, 1);
        if (replay) Anchors.require("vertex-write replay divisor", guards, 1);
        return true;
    }

    /** The loop's location variable, as passed to the attribute descriptor just before the format. */
    private static int attributeLocation(AbstractInsnNode format) {
        for (var instruction = format.getPrevious(); instruction != null; instruction = instruction.getPrevious())
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("org/lwjgl/sdl/SDL_GPUVertexAttribute")
                    && call.name.equals("location")) {
                if (Anchors.previous(call) instanceof VarInsnNode load && load.getOpcode() == Opcodes.ILOAD)
                    return load.var;
                break;
            }
        throw new IllegalStateException("Default vertex attribute location changed in buildVertexInput");
    }

    private static MethodInsnNode call(String name, String descriptor) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, Endpoint.VERTEX_INPUTS.owner(), name, descriptor, false);
    }
}
