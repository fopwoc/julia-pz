package dev.fopwoc.julia.transform.load;

import dev.fopwoc.julia.transform.Endpoint;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Target-aware clears, moved/storage samplers and vertex-write capture with scoped replay bindings. */
public final class SamplerBindingPass {
    private static final String ROOT = "com.gtnewhorizons.angelica.sdlgpu.";
    private static final String STATE = ROOT.replace('.', '/') + "frame/ContextState";
    private static final Map<String, Integer> READS = Map.of(
            ROOT + "resource.FBOClearTracker",
            1,
            ROOT + "sampler.StorageTextureBinder",
            1,
            ROOT + "sampler.MovedSamplerCopies",
            3);

    private SamplerBindingPass() {}

    public static byte[] adapt(String name, byte[] bytes) {
        boolean capture = name.equals(ROOT + "compute.VertexWriteReplayer"),
                compute = name.equals(ROOT + "compute.ComputeBinder");
        Integer count = READS.get(name);
        if (count == null && !capture && !compute) return bytes;
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        int edits = 0;
        for (var method : node.methods) {
            if (compute && method.name.equals("setSamplerOverrides") && method.desc.equals("([J)V")) {
                var head = new InsnList();
                head.add(new VarInsnNode(Opcodes.ALOAD, 1));
                head.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        Endpoint.TEXTURE_BINDING_HOOKS.owner(),
                        "samplerOverride",
                        "([J)V",
                        false));
                method.instructions.insert(head);
                edits++;
            }
            for (var instruction : method.instructions.toArray()) {
                if (count != null
                        && instruction instanceof MethodInsnNode invoke
                        && invoke.owner.equals(STATE)
                        && invoke.name.equals("boundTexture")
                        && invoke.desc.equals("(I)I")) {
                    method.instructions.set(invoke, sampler());
                    edits++;
                }
                if (capture
                        && method.name.equals("capture")
                        && instruction instanceof FieldInsnNode field
                        && field.owner.equals(STATE)
                        && field.name.equals("boundTextures")) {
                    var read = field.getNext();
                    while (read != null && read.getOpcode() != Opcodes.IALOAD) read = read.getNext();
                    if (read == null) throw new IllegalStateException("Vertex-write sampler capture changed");
                    method.instructions.remove(field);
                    method.instructions.set(read, sampler());
                    edits++;
                }
            }
        }
        int expected = count == null ? 1 : count;
        if (edits != expected)
            throw new IllegalStateException(
                    "Sampler binding integration changed: " + name + " anchors=" + edits + " expected=" + expected);
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static MethodInsnNode sampler() {
        return new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                Endpoint.BUFFER_TEXTURE_HOOKS.owner(),
                "samplerBinding",
                "(L" + STATE + ";I)I",
                false);
    }
}
