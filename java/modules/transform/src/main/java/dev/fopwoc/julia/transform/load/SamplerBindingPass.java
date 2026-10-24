package dev.fopwoc.julia.transform.load;

import dev.fopwoc.julia.transform.Endpoint;
import dev.fopwoc.julia.transform.asm.Anchors;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Target-aware clears, moved/storage samplers and vertex-write capture with scoped replay bindings. */
public final class SamplerBindingPass implements UpstreamPatch {
    private static final String ROOT = "com.gtnewhorizons.angelica.sdlgpu.";
    private static final Set<String> TARGETS = Set.of(
            ROOT + "resource.FBOClearTracker",
            ROOT + "sampler.StorageTextureBinder",
            ROOT + "sampler.MovedSamplerCopies",
            ROOT + "compute.VertexWriteReplayer",
            ROOT + "compute.ComputeBinder");
    private static final String STATE = ROOT.replace('.', '/') + "frame/ContextState";
    private static final Map<String, Integer> READS = Map.of(
            ROOT + "resource.FBOClearTracker",
            1,
            ROOT + "sampler.StorageTextureBinder",
            1,
            ROOT + "sampler.MovedSamplerCopies",
            3);

    @Override
    public boolean targets(String name) {
        return TARGETS.contains(name);
    }

    @Override
    public boolean apply(String name, ClassNode node) {
        boolean capture = name.equals(ROOT + "compute.VertexWriteReplayer"),
                compute = name.equals(ROOT + "compute.ComputeBinder");
        Integer count = READS.get(name);
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
            if (count != null)
                edits += Anchors.replaceCalls(method, STATE, "boundTexture", "(I)I", SamplerBindingPass::sampler);
            for (var instruction : method.instructions.toArray()) {
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
        Anchors.require("sampler binding integration in " + name, edits, count == null ? 1 : count);
        return true;
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
