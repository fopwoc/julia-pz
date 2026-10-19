package dev.fopwoc.julia.transform.load;

import dev.fopwoc.julia.transform.Endpoint;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Full-width divisor hashing, native pipeline creation, and protection from stale legacy layout repair. */
public final class VertexInputPass {
    private static final String ROOT = "com.gtnewhorizons.angelica.sdlgpu.";
    private static final String STATE = ROOT.replace('.', '/') + "frame/ContextState";

    private VertexInputPass() {}

    public static byte[] adapt(String name, byte[] bytes) {
        boolean pipeline = name.equals(ROOT + "pipeline.PipelineCache");
        boolean glsm = name.equals("com.gtnewhorizons.angelica.glsm.GLStateManager");
        boolean replay = name.equals(ROOT + "compute.VertexWriteReplayer");
        if (!pipeline && !glsm && !replay) return bytes;
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        int hashes = 0, creates = 0, protects = 0, guards = 0;
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
                if (replay
                        && instruction instanceof MethodInsnNode invoke
                        && invoke.owner.equals("java/lang/Math")
                        && invoke.name.equals("min")
                        && invoke.desc.equals("(II)I")) {
                    var previous = invoke.getPrevious();
                    while (previous != null && previous.getOpcode() == -1) previous = previous.getPrevious();
                    if (previous instanceof LdcInsnNode value
                            && Integer.valueOf(65535).equals(value.cst)) {
                        method.instructions.set(invoke, call("replayDivisor", "(II)I"));
                        guards++;
                    }
                }
            }
        }
        if (pipeline && (hashes != 3 || creates != 1) || glsm && protects != 1 || replay && guards != 1)
            throw new IllegalStateException("Vertex-input integration changed: " + name + " anchors=" + hashes + ","
                    + creates + "," + protects + "," + guards);
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static MethodInsnNode call(String name, String descriptor) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, Endpoint.VERTEX_INPUTS.owner(), name, descriptor, false);
    }
}
